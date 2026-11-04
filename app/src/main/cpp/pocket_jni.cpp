// pocket_jni.cpp — thin llama.cpp JNI bridge for PocketNotebook.
//
// Full engine: expects llama.cpp checked out at <repo>/native/llama.cpp
// (see docs/BUILD.md); CMake static-links libllama into this one .so.
// Kit build (no checkout): every call returns a benign failure and
// nativePing() returns 0 so the app can show "engine not built".
//
// llama.cpp API pinned at commit 6cb3e672e8d10fd4feab1a048bf2967c40623133.

#include <jni.h>
#include <android/log.h>

#include <atomic>
#include <cstdint>
#include <cstring>
#include <functional>
#include <mutex>
#include <string>
#include <unordered_map>
#include <vector>

#if POCKET_WITH_LLAMA
#include <llama.h>
#endif

#define TAG "PocketLlm"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

constexpr int32_t kMagic = 0x504C4D31; // "PLM1"

struct Handle {
    struct llama_model *   model = nullptr;
    struct llama_context * ctx = nullptr;
    const struct llama_vocab * vocab = nullptr;
    bool embedding = false;
    // Resolved reasoning-marker token ids (LLAMA_TOKEN_NULL when the model
    // has no <think>/</think> protocol). Resolved lazily per handle.
    llama_token think_open = LLAMA_TOKEN_NULL;
    llama_token think_close = LLAMA_TOKEN_NULL;
    std::atomic<bool> abort{false};
    std::mutex mutex;
};

std::unordered_map<int64_t, Handle *> g_handles;
std::mutex g_map_mutex;
int64_t g_next_id = 0;
bool g_backend_ready = false;
jclass g_bridge_class = nullptr;    // com.pocketnotebook.app.core.native.PocketLlm
jmethodID g_bridge_emit = nullptr;  // PocketLlm.emitToken(J, String)
jmethodID g_bridge_done = nullptr;  // PocketLlm.onDone(J)

// Reasoning-model think markers (byte-exact constants — never spell these as
// string literals, tooling mangles the bytes). LFM3 / Qwen3 protocol:
//   <think> ... </think>  — an EMPTY think block after the assistant marker
//   means "answer directly" (the template's own enable_thinking=false path).
#if POCKET_WITH_LLAMA
static const char THINK_OPEN [] = {'<','t','h','i','n','k','>',0};
static const char THINK_CLOSE[] = {'<','/','t','h','i','n','k','>',0};
#endif

// Last engine error, captured from the llama.cpp log (load failures, ctx
// init failures, decode failures…). Surfaced to Kotlin via nativeLastError()
// so the UI can show WHY the model is dead instead of failing silently.
std::mutex g_err_mutex;
std::string g_last_error;

void set_last_error(const std::string & msg) {
    std::lock_guard<std::mutex> lk(g_err_mutex);
    // Skip an exact repeat of the last line: llama.cpp fires per-call warnings
    // (e.g. the embeddings output-override line, once per embed) and without
    // this the captured "engine error" grew into a wall of identical pink
    // lines on the Diagnostics screen.
    if (!msg.empty()) {
        const std::string last_nl = msg + "\n";
        if (g_last_error == msg ||
            (g_last_error.size() >= last_nl.size() &&
             g_last_error.compare(g_last_error.size() - last_nl.size(),
                                  last_nl.size(), last_nl) == 0)) {
            return;
        }
    }
    if (g_last_error.size() > 2048) g_last_error.clear(); // keep the tail fresh
    g_last_error += msg;
    if (!g_last_error.empty() && g_last_error.back() != '\n') g_last_error += '\n';
}

// GPU status, captured from the llama.cpp log during model load:
//  - "ggml_opencl: default device: 'Adreno (TM) 825 (…)' (backend device scan)
//  - "load_tensors: offloaded 25/25 layers to GPU"    (offload result)
// Exposed via nativeGpuInfo() so Diagnostics can prove the GPU is really on.
#if POCKET_WITH_LLAMA
std::mutex g_gpu_mutex;
std::string g_gpu_device;
std::string g_gpu_offload;

void set_gpu_info(const std::string & line) {
    if (line.find("ggml_opencl: default device:") != std::string::npos) {
        std::lock_guard<std::mutex> lk(g_gpu_mutex);
        g_gpu_device = line;  // keep the last one
    } else if (line.find("layers to GPU") != std::string::npos) {
        std::lock_guard<std::mutex> lk(g_gpu_mutex);
        g_gpu_offload = line;
    }
}
#endif

void clear_last_error() {
    std::lock_guard<std::mutex> lk(g_err_mutex);
    g_last_error.clear();
}

// Forward llama.cpp logs to logcat; remember ERROR/WARN lines as the
// user-facing failure reason (llama.cpp reports load failures only through
// this log — its C API just returns nullptr).
#if POCKET_WITH_LLAMA
void pocket_log_callback(ggml_log_level level, const char * text, void *) {
    if (text == nullptr) return;
    const size_t n = strnlen(text, 1024);
    std::string line(text, n);
    // GPU status lines arrive at INFO level — capture before the level filter.
    set_gpu_info(line);
    // WARN = 3, ERROR = 4, CONT = 5 (continuation of the previous line).
    if (level >= GGML_LOG_LEVEL_WARN) {
        __android_log_print(level >= GGML_LOG_LEVEL_ERROR ? ANDROID_LOG_ERROR : ANDROID_LOG_WARN,
                            TAG, "%s", line.c_str());
        // Benign llama-batch warning: a logits==NULL batch with embeddings gets
        // its outputs overridden (which IS the intended behavior here) — not
        // a failure, so never surface it as an "engine error".
        // (nativeEmbed now marks outputs explicitly; this is belt & braces.)
        if (line.find("embeddings required but some input tokens were not "
                      "marked as outputs") != std::string::npos) {
            return;
        }
        set_last_error(line);
    } else {
        __android_log_print(ANDROID_LOG_INFO, TAG, "%s", line.c_str());
    }
}
#endif

std::string jstr(JNIEnv * env, jstring s) {
    if (!s) return {};
    const char * c = env->GetStringUTFChars(s, nullptr);
    std::string out(c ? c : "");
    env->ReleaseStringUTFChars(s, c);
    return out;
}

std::string json_escape(const std::string & in) {
    std::string out;
    out.reserve(in.size());
    for (char ch : in) {
        switch (ch) {
            case '"':  out += "\\\""; break;
            case '\\': out += "\\\\"; break;
            case '\n': out += "\\n";  break;
            case '\r': out += "\\r";  break;
            case '\t': out += "\\t";  break;
            default:
                if ((unsigned char) ch < 0x20) { out += ' '; }
                else out += ch;
        }
    }
    return out;
}

Handle * find_handle(int64_t id) {
    std::lock_guard<std::mutex> lk(g_map_mutex);
    auto it = g_handles.find(id);
    return it == g_handles.end() ? nullptr : it->second;
}

bool abort_cb(void * user) {
    auto * h = static_cast<Handle *>(user);
    return h->abort.load();
}

#if POCKET_WITH_LLAMA
// Does this model's chat template use the <think>/</think> protocol?
// (LFM3, Qwen3 and friends.) The template string literally contains the
// marker literals when it does.
static bool template_has_think_protocol(const char * tmpl) {
    if (tmpl == nullptr) return false;
    const std::string t(tmpl);
    return t.find(THINK_OPEN) != std::string::npos &&
           t.find(THINK_CLOSE) != std::string::npos;
}

// Resolve the marker token ids by exact-text vocab scan. These tokens are
// type-UNDEFINED in some GGUFs (the tokenizer never emits them from text),
// so tokenize() cannot find them — but the model was trained to sample them.
static void resolve_think_tokens(Handle * h) {
    if (h->think_open != LLAMA_TOKEN_NULL) return;
    const int32_t n_vocab = llama_vocab_n_tokens(h->vocab);
    for (llama_token id = 0; id < n_vocab; id++) {
        const char * txt = llama_vocab_get_text(h->vocab, id);
        if (txt == nullptr) continue;
        if (strcmp(txt, THINK_OPEN) == 0) h->think_open = id;
        else if (strcmp(txt, THINK_CLOSE) == 0) h->think_close = id;
    }
}

// Render a token's full text (special tokens included) into a small buffer.
static std::string token_full_text(const struct llama_vocab * vocab, llama_token tok) {
    char piece[128];
    const int n = llama_token_to_piece(vocab, tok, piece, sizeof(piece), 0, true);
    if (n <= 0) return {};
    return std::string(piece, (size_t) n);
}

// Embedding contexts decode the whole sequence in one batch (non-causal):
// never hand llama_decode more tokens than the context's n_batch.
static int32_t n_batch_limit(const Handle * h) {
    return 1024;
}
#endif

void emit_piece(JNIEnv * env, int64_t callback_id, const std::string & piece) {
    if (callback_id == 0 || g_bridge_class == nullptr) return;
    jstring jp = env->NewStringUTF(piece.c_str());
    env->CallStaticVoidMethod(g_bridge_class, g_bridge_emit, (jlong) callback_id, jp);
    if (env->ExceptionCheck()) env->ExceptionClear();
    env->DeleteLocalRef(jp);
}

// Robust tokenize: grows the buffer until the result is strictly smaller,
// and also handles the legacy "returns negative needed size" contract.
int32_t tokenize(const struct llama_vocab * vocab, const std::string & text,
                 bool add_special, bool parse_special, std::vector<llama_token> & out) {
    size_t cap = 2048;
    for (int attempt = 0; attempt < 8; attempt++) {
        out.assign(cap, 0);
        int32_t n = llama_tokenize(vocab, text.data(), (int32_t) text.size(),
                                   out.data(), (int32_t) out.size(),
                                   add_special, parse_special);
        if (n < 0) {
            const int32_t needed = -n;
            if (needed <= (int32_t) out.size()) { out.clear(); return n; } // hard error
            cap = (size_t) needed + 16;
            continue;
        }
        if (n < (int32_t) out.size()) {          // definitive result
            if (n > 0) out.resize((size_t) n); else out.clear();
            return n;
        }
        cap = out.size() * 2 + 512;              // buffer was exactly full — grow
    }
    out.clear();
    return -1;
}

} // namespace

extern "C" {

JNIEXPORT jint JNICALL
Java_com_pocketnotebook_app_core_native_PocketLlm_nativePing(JNIEnv *, jobject) {
#if POCKET_WITH_LLAMA
    return kMagic;
#else
    return 0;
#endif
}

JNIEXPORT void JNICALL
Java_com_pocketnotebook_app_core_native_PocketLlm_nativeBackendInit(JNIEnv * env, jobject) {
#if POCKET_WITH_LLAMA
    if (g_backend_ready) return;
    llama_backend_init();
    llama_log_set(pocket_log_callback, nullptr);
    clear_last_error();
    jclass local = env->FindClass("com/pocketnotebook/app/core/native/PocketLlm");
    if (local != nullptr) {
        g_bridge_class = (jclass) env->NewGlobalRef(local);
        g_bridge_emit = env->GetStaticMethodID(g_bridge_class, "emitToken", "(JLjava/lang/String;)V");
        g_bridge_done = env->GetStaticMethodID(g_bridge_class, "onDone", "(J)V");
        env->DeleteLocalRef(local);
    }
    g_backend_ready = true;
    LOGI("backend ready (llama.cpp pinned 6cb3e67)");
#else
    (void) env;
#endif
}

JNIEXPORT jstring JNICALL
Java_com_pocketnotebook_app_core_native_PocketLlm_nativeLastError(JNIEnv * env, jobject) {
    std::lock_guard<std::mutex> lk(g_err_mutex);
    std::string out;
    // Keep only the last few lines — the reason is at the end of the log.
    const size_t n = g_last_error.size();
    if (n > 600) {
        out = g_last_error.substr(n - 600);
        const size_t nl = out.find('\n');
        if (nl != std::string::npos) out = out.substr(nl + 1);
    } else {
        out = g_last_error;
    }
    while (!out.empty() && (out.back() == '\n' || out.back() == ' ')) out.pop_back();
    return env->NewStringUTF(out.c_str());
}

JNIEXPORT jlong JNICALL
Java_com_pocketnotebook_app_core_native_PocketLlm_nativeLoadModel(JNIEnv * env, jobject,
        jstring jpath, jint n_ctx, jint n_threads, jboolean embedding, jint kv_quant) {
#if POCKET_WITH_LLAMA
    if (!g_backend_ready) { set_last_error("backend not initialized"); return 0; }
    std::string path = jstr(env, jpath);
    clear_last_error();
    auto mp = llama_model_default_params();
    struct llama_model * model = llama_model_load_from_file(path.c_str(), mp);
    if (!model) {
        set_last_error("model load failed: " + path);
        LOGE("load failed: %s", path.c_str());
        return 0;
    }

    auto * h = new (std::nothrow) Handle();
    if (!h) { llama_model_free(model); return 0; }
    h->model = model;

    auto cp = llama_context_default_params();
    cp.n_ctx = (uint32_t) n_ctx;
    // Non-causal (embedding) contexts must fit the whole sequence in one
    // ubatch (GGML_ASSERT), and embed inputs are capped at 1024 tokens —
    // keep their ubatch at 1024. Causal chat contexts prefill in 512-token
    // chunks (see nativeComplete) so a small ubatch trims compute buffers.
    if (embedding == JNI_TRUE) {
        cp.n_batch = 1024;
        cp.n_ubatch = 1024;
    } else {
        cp.n_batch = 2048;
        cp.n_ubatch = 512;
    }
    cp.n_threads = n_threads;
    cp.n_threads_batch = n_threads;
    // KV-cache quantization: 0 = F16, 4 = Q4_0, 8 = Q8_0. Quantized K/V
    // shrinks the cache ~4x/~2x so long contexts fit in RAM. Quantized V
    // requires Flash Attention — llama.cpp enforces this; we enable FA for
    // every quantized request (and it is faster on CPU anyway).
    if (kv_quant == 4) {
        cp.type_k = GGML_TYPE_Q4_0;
        cp.type_v = GGML_TYPE_Q4_0;
        cp.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_ENABLED;
    } else if (kv_quant == 8) {
        cp.type_k = GGML_TYPE_Q8_0;
        cp.type_v = GGML_TYPE_Q8_0;
        cp.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_ENABLED;
    }
    if (embedding == JNI_TRUE) {
        cp.embeddings = true;
        cp.pooling_type = LLAMA_POOLING_TYPE_MEAN;
        cp.attention_type = LLAMA_ATTENTION_TYPE_NON_CAUSAL;
        cp.type_k = GGML_TYPE_F16; // exact embeddings; tiny ctx anyway
        cp.type_v = GGML_TYPE_F16;
    }
    cp.abort_callback = abort_cb;
    cp.abort_callback_data = h;

    struct llama_context * ctx = llama_init_from_model(model, cp);
    if (!ctx) {
        set_last_error("context init failed (ctx=" + std::to_string(n_ctx) +
                       ", kv=" + std::to_string(kv_quant) + ")");
        LOGE("ctx init failed");
        llama_model_free(model);
        delete h;
        return 0;
    }

    h->ctx = ctx;
    h->vocab = llama_model_get_vocab(model);
    h->embedding = (embedding == JNI_TRUE);

    std::lock_guard<std::mutex> lk(g_map_mutex);
    const int64_t id = ++g_next_id;
    g_handles[id] = h;
    LOGI("loaded handle %lld (%s, ctx=%d, threads=%d, kv=%d)",
         (long long) id, path.c_str(), (int) n_ctx, (int) n_threads, (int) kv_quant);
    return (jlong) id;
#else
    (void) env; (void) jpath; (void) n_ctx; (void) n_threads; (void) embedding; (void) kv_quant;
    return 0;
#endif
}

JNIEXPORT void JNICALL
Java_com_pocketnotebook_app_core_native_PocketLlm_nativeUnload(JNIEnv *, jobject, jlong id) {
#if POCKET_WITH_LLAMA
    // Take the handle out of the map first, then wait for any in-flight
    // decode on it (h->mutex) before freeing — freeing a context mid-decode
    // is a use-after-free (abort callback + KV cache still in use).
    Handle * h = nullptr;
    {
        std::lock_guard<std::mutex> lk(g_map_mutex);
        auto it = g_handles.find(id);
        if (it != g_handles.end()) { h = it->second; g_handles.erase(it); }
    }
    if (h) {
        {
            std::lock_guard<std::mutex> lk2(h->mutex);
            llama_free(h->ctx);
            llama_model_free(h->model);
        }
        delete h;
        LOGI("unloaded handle %lld", (long long) id);
    }
#else
    (void) id;
#endif
}

JNIEXPORT jstring JNICALL
Java_com_pocketnotebook_app_core_native_PocketLlm_nativeMeta(JNIEnv * env, jobject, jlong id) {
#if POCKET_WITH_LLAMA
    Handle * h = find_handle((int64_t) id);
    if (!h) return env->NewStringUTF("{}");

    char desc[256] = {0};
    llama_model_desc(h->model, desc, sizeof(desc));

    char name[256] = {0};
    llama_model_meta_val_str(h->model, "general.name", name, sizeof(name));
    char arch[64] = {0};
    llama_model_meta_val_str(h->model, "general.architecture", arch, sizeof(arch));

    const char * tmpl = llama_model_chat_template(h->model, nullptr);

    std::string json = "{";
    json += "\"desc\":\"" + json_escape(desc) + "\",";
    json += "\"name\":\"" + json_escape(name) + "\",";
    json += "\"arch\":\"" + json_escape(arch) + "\",";
    json += "\"nCtxTrain\":" + std::to_string(llama_model_n_ctx_train(h->model)) + ",";
    json += "\"nParams\":" + std::to_string(llama_model_n_params(h->model)) + ",";
    json += "\"sizeBytes\":" + std::to_string(llama_model_size(h->model)) + ",";
    json += "\"nEmbd\":" + std::to_string(llama_model_n_embd(h->model)) + ",";
    json += "\"hasTemplate\":" + std::string(tmpl ? "true" : "false") + ",";
    json += "\"embedding\":" + std::string(h->embedding ? "true" : "false");
    json += "}";
    return env->NewStringUTF(json.c_str());
#else
    (void) id;
    return env->NewStringUTF("{}");
#endif
}

JNIEXPORT jint JNICALL
Java_com_pocketnotebook_app_core_native_PocketLlm_nativeTokenCount(JNIEnv * env, jobject,
        jlong id, jstring jtext) {
#if POCKET_WITH_LLAMA
    Handle * h = find_handle((int64_t) id);
    if (!h) return -1;
    std::string text = jstr(env, jtext);
    std::vector<llama_token> toks;
    return tokenize(h->vocab, text, false, false, toks);
#else
    (void) id; (void) jtext;
    return -1;
#endif
}

JNIEXPORT jstring JNICALL
Java_com_pocketnotebook_app_core_native_PocketLlm_nativeApplyTemplate(JNIEnv * env, jobject,
        jlong id, jobjectArray roles, jobjectArray contents, jboolean add_assist) {
#if POCKET_WITH_LLAMA
    Handle * h = find_handle((int64_t) id);
    if (!h) return env->NewStringUTF("");

    const jsize n = env->GetArrayLength(roles);
    std::vector<std::string> role_store, content_store;
    role_store.reserve((size_t) n);
    content_store.reserve((size_t) n);
    for (jsize i = 0; i < n; i++) {
        auto * r = (jstring) env->GetObjectArrayElement(roles, i);
        auto * c = (jstring) env->GetObjectArrayElement(contents, i);
        role_store.push_back(jstr(env, r));
        content_store.push_back(jstr(env, c));
        env->DeleteLocalRef(r);
        env->DeleteLocalRef(c);
    }
    std::vector<struct llama_chat_message> msgs;
    msgs.reserve((size_t) n);
    for (jsize i = 0; i < n; i++) {
        msgs.push_back({role_store[(size_t) i].c_str(), content_store[(size_t) i].c_str()});
    }

    const char * tmpl = llama_model_chat_template(h->model, nullptr);
    if (tmpl == nullptr) {
        // Fallback for GGUFs without a chat template.
        std::string out;
        for (jsize i = 0; i < n; i++) {
            out += role_store[(size_t) i];
            out += ": ";
            out += content_store[(size_t) i];
            out += "\n";
        }
        if (add_assist == JNI_TRUE) out += "Assistant: ";
        return env->NewStringUTF(out.c_str());
    }

    std::string buf(4096, 0);
    int32_t need = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(),
                                             add_assist == JNI_TRUE,
                                             buf.data(), (int32_t) buf.size());
    if (need < 0) return env->NewStringUTF("");
    if (need > (int32_t) buf.size()) {
        buf.resize((size_t) need + 16, 0);
        need = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(),
                                         add_assist == JNI_TRUE,
                                         buf.data(), (int32_t) buf.size());
        if (need < 0) return env->NewStringUTF("");
    }
    buf.resize((size_t) need);

    // Reasoning models: end the assistant turn with an EMPTY think block so
    // the model answers directly instead of streaming its chain-of-thought
    // (the template's own enable_thinking=false branch — llama.cpp's template
    // formatter ignores that flag, so we apply it ourselves). nativeComplete
    // swaps this trailing text for the canonical marker token ids.
    if (add_assist == JNI_TRUE && template_has_think_protocol(tmpl)) {
        buf += THINK_OPEN;
        buf += THINK_CLOSE;
    }
    return env->NewStringUTF(buf.c_str());
#else
    (void) id; (void) roles; (void) contents; (void) add_assist;
    return env->NewStringUTF("");
#endif
}

JNIEXPORT jstring JNICALL
Java_com_pocketnotebook_app_core_native_PocketLlm_nativeComplete(JNIEnv * env, jobject,
        jlong id, jstring jprompt, jstring jgrammar, jstring jgrammar_root,
        jfloat temp, jint top_k, jfloat top_p, jint seed, jint max_tokens,
        jstring jstop, jlong callback_id) {
#if POCKET_WITH_LLAMA
    Handle * h = find_handle((int64_t) id);
    if (!h || h->embedding) return env->NewStringUTF("");

    std::lock_guard<std::mutex> lk(h->mutex);
    h->abort.store(false);
    // Fresh KV cache per completion: each call carries the full prompt, so
    // without this the cache would accumulate every previous question until
    // the context overflows (question 2+ would silently return "").
    llama_memory_clear(llama_get_memory(h->ctx), true);

    std::string prompt = jstr(env, jprompt);
    std::string grammar = jstr(env, jgrammar);
    std::string root = jstr(env, jgrammar_root);
    std::string stop = jstr(env, jstop);

    std::vector<llama_token> tokens;
    tokenize(h->vocab, prompt, false, true, tokens); // template output already carries specials
    if (tokens.empty()) { set_last_error("prompt tokenization failed"); return env->NewStringUTF(""); }

    // Canonicalize the trailing empty-think-block: nativeApplyTemplate appended
    // the OFF markers as TEXT (the tokenizer cannot emit the marker token ids
    // from text — they are undefined-type in some GGUFs). Swap the trailing
    // text tokens for the canonical marker tokens so the model sees exactly
    // what its own template would have produced.
    resolve_think_tokens(h);
    if (h->think_open != LLAMA_TOKEN_NULL && h->think_close != LLAMA_TOKEN_NULL &&
        tokens.size() >= 2) {
        const std::string offseq = std::string(THINK_OPEN) + THINK_CLOSE;
        for (size_t k = 2; k <= 8 && k <= tokens.size(); k++) {
            std::string joined;
            for (size_t i = tokens.size() - k; i < tokens.size(); i++)
                joined += token_full_text(h->vocab, tokens[i]);
            if (joined == offseq) {
                tokens.resize(tokens.size() - k);
                tokens.push_back(h->think_open);
                tokens.push_back(h->think_close);
                break;
            }
        }
    }

    // Chunked prefill: llama.cpp hard-asserts n_tokens <= n_batch, and a
    // grounded prompt with sources can be thousands of tokens. Feed the
    // prompt through in <=512-token chunks so any prompt size works.
    bool prefill_failed = false;
    {
        const int32_t total = (int32_t) tokens.size();
        int32_t pos = 0;
        while (pos < total) {
            if (h->abort.load()) { prefill_failed = true; set_last_error("aborted"); break; }
            const int32_t chunk = total - pos < 512 ? total - pos : 512;
            llama_batch pbatch = llama_batch_get_one(tokens.data() + pos, chunk);
            if (llama_decode(h->ctx, pbatch) != 0) {
                prefill_failed = true;
                set_last_error("decode failed during prefill (ctx full? " +
                               std::to_string(total) + " prompt tokens)");
                break;
            }
            pos += chunk;
        }
    }
    if (prefill_failed) return env->NewStringUTF("");

    auto sp = llama_sampler_chain_default_params();
    struct llama_sampler * chain = llama_sampler_chain_init(sp);
    if (top_k > 0) llama_sampler_chain_add(chain, llama_sampler_init_top_k(top_k));
    if (top_p < 1.0f) llama_sampler_chain_add(chain, llama_sampler_init_top_p(top_p, 1));
    llama_sampler_chain_add(chain, llama_sampler_init_temp(temp));
    const uint32_t useed = seed >= 0 ? (uint32_t) seed
                                      : (uint32_t) std::hash<std::string>{}(prompt);
    llama_sampler_chain_add(chain, llama_sampler_init_dist(useed));
    if (!grammar.empty()) {
        struct llama_sampler * g = llama_sampler_init_grammar(h->vocab, grammar.c_str(),
                                                              root.empty() ? "root" : root.c_str());
        if (g) llama_sampler_chain_add(chain, g);
    }

    // Stop on end-of-sentence / end-of-turn — but never accept an immediate
    // eos with zero output (a template mismatch can make the model emit eos
    // as its very first token; skipping a few of those keeps the answer
    // alive instead of silently returning "").
    const llama_token eos = llama_vocab_eos(h->vocab);
    const llama_token eot = llama_vocab_eot(h->vocab);
    int eos_skips = 0;
    const int32_t n_ctx = (int32_t) llama_n_ctx(h->ctx);
    std::string out;
    out.reserve(4096);

    // Prompt is already decoded (chunked prefill above): sample from the
    // last position, then decode one token at a time.
    //
    // Hidden chain-of-thought: if the model still opens a think block (it
    // should not — the prompt ends with an empty one — but any model can),
    // everything inside is suppressed from output, emission and the
    // max_tokens budget; only a hard cap bounds runaway reasoning.
    int32_t used = (int32_t) tokens.size();
    int32_t visible = 0;        // tokens that count toward max_tokens
    int32_t generated = 0;      // all sampled tokens (hard-capped)
    const int32_t hard_cap = max_tokens + 2048;
    bool in_think = false;

    while (visible < max_tokens && generated < hard_cap && used + 4 < n_ctx) {
        if (h->abort.load()) break;
        const llama_token tok = llama_sampler_sample(chain, h->ctx, -1);
        if (tok == eos || tok == eot) {
            if (!out.empty() || eos_skips >= 3) break;
            eos_skips++;
            continue; // do not feed it; resample from the same logits
        }
        eos_skips = 0;
        generated++;

        // Hidden chain-of-thought: markers and reasoning are suppressed from
        // the output/emission/budget, but still DECODED (the model must see
        // its own tokens in context, and skipping the decode would re-sample
        // stale logits forever).
        bool suppressed_token = false;
        if (h->think_open != LLAMA_TOKEN_NULL && tok == h->think_open) {
            in_think = true;
            suppressed_token = true;
        } else if (h->think_close != LLAMA_TOKEN_NULL && tok == h->think_close) {
            in_think = false;
            suppressed_token = true;
        } else if (in_think) {
            suppressed_token = true;
        }

        if (!suppressed_token) {
            char piece[96];
            const int pn = llama_token_to_piece(h->vocab, tok, piece, sizeof(piece), 0, false);
            if (pn > 0) out.append(piece, (size_t) pn);

            if (!stop.empty() && out.size() >= stop.size() &&
                out.compare(out.size() - stop.size(), stop.size(), stop) == 0) {
                out.resize(out.size() - stop.size());
                break;
            }

            if (callback_id != 0 && pn > 0) {
                emit_piece(env, (int64_t) callback_id, std::string(piece, (size_t) pn));
            }
            visible++;
        }

        used++;

        llama_token cur = tok;
        llama_batch batch = llama_batch_get_one(&cur, 1);
        if (llama_decode(h->ctx, batch) != 0) {
            set_last_error("decode failed during generation (gen=" + std::to_string(visible) + ")");
            LOGI("decode ended early (abort or ctx full), gen=%d", visible);
            break;
        }
    }

    llama_sampler_free(chain);
    // Name the silent stop: when the context filled before the token budget,
    // the caller sees a short answer with no error — recording the reason
    // makes such truncations diagnosable in Diagnostics instead of a mystery.
    if (visible < max_tokens && used + 4 >= n_ctx && !out.empty()) {
        set_last_error("context limit: prompt+output filled n_ctx=" +
                       std::to_string(n_ctx) + " after " +
                       std::to_string(visible) + " output tokens (output truncated)");
    }
    if (callback_id != 0 && g_bridge_class != nullptr) {
        env->CallStaticVoidMethod(g_bridge_class, g_bridge_done, (jlong) callback_id);
        if (env->ExceptionCheck()) env->ExceptionClear();
    }
    LOGI("completion done: %d visible tokens, %zu chars", visible, out.size());
    return env->NewStringUTF(out.c_str());
#else
    (void) id; (void) jprompt; (void) jgrammar; (void) jgrammar_root; (void) temp;
    (void) top_k; (void) top_p; (void) seed; (void) max_tokens; (void) jstop; (void) callback_id;
    return env->NewStringUTF("");
#endif
}

JNIEXPORT jstring JNICALL
Java_com_pocketnotebook_app_core_native_PocketLlm_nativeGpuInfo(JNIEnv * env, jobject) {
#if POCKET_WITH_LLAMA
    std::lock_guard<std::mutex> lk(g_gpu_mutex);
    std::string out;
#if POCKET_WITH_OPENCL
    if (!g_gpu_device.empty()) {
        out = g_gpu_device;
        if (!g_gpu_offload.empty()) {
            out += "\n";
            out += g_gpu_offload;
        }
    } else {
        out = "OpenCL backend compiled in; no device reported yet "
              "(loads after the first model load; CPU fallback otherwise).";
    }
#else
    out = "CPU-only build (OpenCL backend not compiled in).";
#endif
    return env->NewStringUTF(out.c_str());
#else
    (void) env;
    return env->NewStringUTF("kit build (no native engine).");
#endif
}

JNIEXPORT void JNICALL
Java_com_pocketnotebook_app_core_native_PocketLlm_nativeAbort(JNIEnv *, jobject, jlong id) {
#if POCKET_WITH_LLAMA
    Handle * h = find_handle((int64_t) id);
    if (h) h->abort.store(true);
#else
    (void) id;
#endif
}

// Context size of a loaded model handle, in tokens — the Kotlin layer uses
// it to size the Studio digest so prompt + generation fit the context
// (alpha.9 could fail the whole prefill when the digest alone overflowed
// a 4096-token context).
JNIEXPORT jint JNICALL
Java_com_pocketnotebook_app_core_native_PocketLlm_nativeCtx(JNIEnv *, jobject, jlong id) {
#if POCKET_WITH_LLAMA
    Handle * h = find_handle((int64_t) id);
    if (!h || !h->ctx) return 0;
    return (jint) llama_n_ctx(h->ctx);
#else
    (void) id;
    return 0;
#endif
}

JNIEXPORT jfloatArray JNICALL
Java_com_pocketnotebook_app_core_native_PocketLlm_nativeEmbed(JNIEnv * env, jobject,
        jlong id, jstring jtext) {
#if POCKET_WITH_LLAMA
    Handle * h = find_handle((int64_t) id);
    if (!h || !h->embedding) return env->NewFloatArray(0);

    std::lock_guard<std::mutex> lk(h->mutex);
    h->abort.store(false);
    // Fresh KV cache per embedding call (single non-causal sequence).
    llama_memory_clear(llama_get_memory(h->ctx), true);

    std::string text = jstr(env, jtext);
    std::vector<llama_token> tokens;
    tokenize(h->vocab, text, true, false, tokens);
    if (tokens.empty()) return env->NewFloatArray(0);
    const llama_token eos = llama_vocab_eos(h->vocab);
    if (eos != LLAMA_TOKEN_NULL && tokens.back() != eos) tokens.push_back(eos);

    // THE CRASH FIX (v0.1.0-alpha.7 crashed on file upload): embedding
    // models like bge-small have n_ctx_train = 512 — their position-embedding
    // table has 512 rows. Feeding one token past that hit
    //   GGML_ASSERT(i01 >= 0 && i01 < ne01)  (ggml-cpu/ops.cpp get_rows)
    //   -> abort() -> whole app dead mid-ingest.
    // Cap the sequence to the model's real limit (minus the eos we appended).
    // Long tails are covered by BM25 and the chunk overlap.
    const int32_t n_ctx_train = (int32_t) llama_model_n_ctx_train(h->model);
    const int32_t n_ctx_now   = (int32_t) llama_n_ctx(h->ctx);
    const int32_t limit       = (std::min(n_ctx_now, n_ctx_train) > 1)
                              ? std::min(n_ctx_now, n_ctx_train) - 1
                              : 1;
    if ((int32_t) tokens.size() > limit) {
        LOGI("embed truncated %zu -> %d tokens (model ctx_train=%d)",
             tokens.size(), limit, n_ctx_train);
        tokens.resize((size_t) limit);
        if (!tokens.empty() && tokens.back() != eos && eos != LLAMA_TOKEN_NULL)
            tokens.back() = eos;
    }
    if (tokens.empty() || (int32_t) tokens.size() > n_batch_limit(h))
        return env->NewFloatArray(0);

    // Mark EVERY token as an output explicitly. llama_batch_get_one leaves
    // batch.logits == NULL; with embeddings enabled llama-batch.cpp then warns
    // "... some input tokens were not marked as outputs -> overriding" on
    // EVERY embed call — once per chunk during ingestion, a pink wall of
    // fake "engine errors" in Diagnostics. Marking all tokens is exactly
    // what the override does (mean pooling over the whole sequence), minus
    // the warning. The vector must outlive llama_decode below.
    std::vector<int8_t> outputs(tokens.size(), 1);
    llama_batch batch = llama_batch_get_one(tokens.data(), (int32_t) tokens.size());
    batch.logits = outputs.data();
    if (llama_decode(h->ctx, batch) != 0) {
        LOGE("embed decode failed");
        set_last_error("embed decode failed");
        return env->NewFloatArray(0);
    }
    const int32_t n_embd = llama_model_n_embd(h->model);
    const float * emb = llama_get_embeddings(h->ctx);
    if (!emb || n_embd <= 0) return env->NewFloatArray(0);

    jfloatArray arr = env->NewFloatArray((jsize) n_embd);
    if (arr == nullptr) return env->NewFloatArray(0);
    env->SetFloatArrayRegion(arr, 0, (jsize) n_embd, emb);
    return arr;
#else
    (void) id; (void) jtext;
    return env->NewFloatArray(0);
#endif
}

} // extern "C"
