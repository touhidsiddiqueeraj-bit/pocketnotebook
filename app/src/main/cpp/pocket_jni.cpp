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

void emit_piece(JNIEnv * env, int64_t callback_id, const std::string & piece) {
    if (callback_id == 0 || g_bridge_class == nullptr) return;
    jstring jp = env->NewStringUTF(piece.c_str());
    env->CallStaticVoidMethod(g_bridge_class, g_bridge_emit, (jlong) callback_id, jp);
    if (env->ExceptionCheck()) env->ExceptionClear();
    env->DeleteLocalRef(jp);
}

// Robust tokenize: handles both "returns count" and "returns negative needed".
int32_t tokenize(const struct llama_vocab * vocab, const std::string & text,
                 bool add_special, bool parse_special, std::vector<llama_token> & out) {
    out.assign(2048, 0);
    int32_t n = llama_tokenize(vocab, text.data(), (int32_t) text.size(),
                               out.data(), (int32_t) out.size(),
                               add_special, parse_special);
    if (n < 0) {
        out.assign((size_t) (-n) + 16, 0);
        n = llama_tokenize(vocab, text.data(), (int32_t) text.size(),
                           out.data(), (int32_t) out.size(),
                           add_special, parse_special);
    }
    if (n > 0) out.resize((size_t) n);
    else out.clear();
    return n;
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

JNIEXPORT jlong JNICALL
Java_com_pocketnotebook_app_core_native_PocketLlm_nativeLoadModel(JNIEnv * env, jobject,
        jstring jpath, jint n_ctx, jint n_threads, jboolean embedding) {
#if POCKET_WITH_LLAMA
    if (!g_backend_ready) return 0;
    std::string path = jstr(env, jpath);
    auto mp = llama_model_default_params();
    struct llama_model * model = llama_model_load_from_file(path.c_str(), mp);
    if (!model) { LOGE("load failed: %s", path.c_str()); return 0; }

    auto * h = new (std::nothrow) Handle();
    if (!h) { llama_model_free(model); return 0; }
    h->model = model;

    auto cp = llama_context_default_params();
    cp.n_ctx = (uint32_t) n_ctx;
    cp.n_batch = 1024;
    cp.n_ubatch = 1024;
    cp.n_threads = n_threads;
    cp.n_threads_batch = n_threads;
    if (embedding == JNI_TRUE) {
        cp.embeddings = true;
        cp.pooling_type = LLAMA_POOLING_TYPE_MEAN;
        cp.attention_type = LLAMA_ATTENTION_TYPE_NON_CAUSAL;
    }
    cp.abort_callback = abort_cb;
    cp.abort_callback_data = h;

    struct llama_context * ctx = llama_init_from_model(model, cp);
    if (!ctx) { LOGE("ctx init failed"); llama_model_free(model); delete h; return 0; }

    h->ctx = ctx;
    h->vocab = llama_model_get_vocab(model);
    h->embedding = (embedding == JNI_TRUE);

    std::lock_guard<std::mutex> lk(g_map_mutex);
    const int64_t id = ++g_next_id;
    g_handles[id] = h;
    LOGI("loaded handle %lld (%s, ctx=%d, threads=%d)",
         (long long) id, path.c_str(), (int) n_ctx, (int) n_threads);
    return (jlong) id;
#else
    (void) env; (void) jpath; (void) n_ctx; (void) n_threads; (void) embedding;
    return 0;
#endif
}

JNIEXPORT void JNICALL
Java_com_pocketnotebook_app_core_native_PocketLlm_nativeUnload(JNIEnv *, jobject, jlong id) {
#if POCKET_WITH_LLAMA
    Handle * h = nullptr;
    {
        std::lock_guard<std::mutex> lk(g_map_mutex);
        auto it = g_handles.find(id);
        if (it != g_handles.end()) { h = it->second; g_handles.erase(it); }
    }
    if (h) {
        llama_free(h->ctx);
        llama_model_free(h->model);
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

    std::string prompt = jstr(env, jprompt);
    std::string grammar = jstr(env, jgrammar);
    std::string root = jstr(env, jgrammar_root);
    std::string stop = jstr(env, jstop);

    std::vector<llama_token> tokens;
    tokenize(h->vocab, prompt, false, true, tokens); // template output already carries specials
    if (tokens.empty()) return env->NewStringUTF("");

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

    const llama_token eos = llama_vocab_eos(h->vocab);
    const int32_t n_ctx = (int32_t) llama_n_ctx(h->ctx);
    std::string out;
    out.reserve(4096);

    llama_token cur = tokens[0];
    llama_batch batch = llama_batch_get_one(tokens.data(), (int32_t) tokens.size());
    int32_t used = (int32_t) tokens.size();
    int32_t generated = 0;

    while (generated < max_tokens && used + 4 < n_ctx) {
        if (llama_decode(h->ctx, batch) != 0) {
            LOGI("decode ended early (abort or ctx full), gen=%d", generated);
            break;
        }
        const llama_token tok = llama_sampler_sample(chain, h->ctx, -1);
        if (tok == eos) break;

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

        used++;
        generated++;
        cur = tok;
        batch = llama_batch_get_one(&cur, 1);
    }

    llama_sampler_free(chain);
    if (callback_id != 0 && g_bridge_class != nullptr) {
        env->CallStaticVoidMethod(g_bridge_class, g_bridge_done, (jlong) callback_id);
        if (env->ExceptionCheck()) env->ExceptionClear();
    }
    LOGI("completion done: %d tokens, %zu chars", generated, out.size());
    return env->NewStringUTF(out.c_str());
#else
    (void) id; (void) jprompt; (void) jgrammar; (void) jgrammar_root; (void) temp;
    (void) top_k; (void) top_p; (void) seed; (void) max_tokens; (void) jstop; (void) callback_id;
    return env->NewStringUTF("");
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

JNIEXPORT jfloatArray JNICALL
Java_com_pocketnotebook_app_core_native_PocketLlm_nativeEmbed(JNIEnv * env, jobject,
        jlong id, jstring jtext) {
#if POCKET_WITH_LLAMA
    Handle * h = find_handle((int64_t) id);
    if (!h || !h->embedding) return env->NewFloatArray(0);

    std::lock_guard<std::mutex> lk(h->mutex);
    h->abort.store(false);

    std::string text = jstr(env, jtext);
    std::vector<llama_token> tokens;
    tokenize(h->vocab, text, true, false, tokens);
    if (tokens.empty()) return env->NewFloatArray(0);
    const llama_token eos = llama_vocab_eos(h->vocab);
    if (tokens.back() != eos) tokens.push_back(eos);
    if (tokens.size() > 1024) tokens.resize(1024); // guard: keep inside embed ctx

    llama_batch batch = llama_batch_get_one(tokens.data(), (int32_t) tokens.size());
    if (llama_decode(h->ctx, batch) != 0) {
        LOGE("embed decode failed");
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
