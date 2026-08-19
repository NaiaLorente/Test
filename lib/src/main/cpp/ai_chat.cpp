#include <android/log.h>
#include <jni.h>
#include <iomanip>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <csignal>
#include <signal.h>
#include <ctime>
#include <string>
#include <unistd.h>
#include <sampling.h>

#include "logging.h"
#include "chat.h"
#include "common.h"
#include "llama.h"

template<class T>
static std::string join(const std::vector<T> &values, const std::string &delim) {
    std::ostringstream str;
    for (size_t i = 0; i < values.size(); i++) {
        str << values[i];
        if (i < values.size() - 1) { str << delim; }
    }
    return str.str();
}

/** Minimal JSON string-body escaping (quotes, backslashes, control chars) - raw UTF-8 bytes pass through unchanged. */
static std::string json_escape(const std::string &s) {
    std::ostringstream out;
    for (const unsigned char c: s) {
        switch (c) {
            case '"':  out << "\\\""; break;
            case '\\': out << "\\\\"; break;
            case '\b': out << "\\b"; break;
            case '\f': out << "\\f"; break;
            case '\n': out << "\\n"; break;
            case '\r': out << "\\r"; break;
            case '\t': out << "\\t"; break;
            default:
                if (c < 0x20) {
                    char buf[8];
                    snprintf(buf, sizeof(buf), "\\u%04x", c);
                    out << buf;
                } else {
                    out << c;
                }
        }
    }
    return out.str();
}

/**
 * LLama resources: context, model, batch and sampler
 */
constexpr int   N_THREADS_MIN           = 2;
constexpr int   N_THREADS_MAX           = 4;
constexpr int   N_THREADS_HEADROOM      = 2;

// Raised again (16384 -> 20480) now that the confirmed-fast daily-driver models are all in the
// 3-4B class, which have plenty of RAM headroom to spare for a bigger KV-cache. This is the main
// lever for "remembers more of the conversation": a bigger raw window means fewer, later
// evictions before the rolling-summary mechanism below ever needs to kick in at all. Kept as a
// moderate step up rather than doubling, since raw context is the only one of these settings that
// risks an OOM kill on-device rather than just a slower/lower-quality result - revisit downward
// again if a bigger model (7B+) becomes the daily driver, the same way it was cut for the 8B
// Stheno experiments.
constexpr int   DEFAULT_CONTEXT_SIZE    = 20480;
constexpr int   OVERFLOW_HEADROOM       = 4;
constexpr int   BATCH_SIZE              = 512;

// Sampler tuning: small (1-3B) phone-class models drift into fabricated/contradictory content
// quickly at high temperature with no repetition penalty. This trades a bit of creativity for
// staying grounded in what has actually been established in the conversation.
constexpr float DEFAULT_SAMPLER_TEMP    = 0.6f; // overridden per-character via setTemperature()
constexpr float SAMPLER_REPEAT_PENALTY  = 1.15f;
constexpr int   SAMPLER_REPEAT_LAST_N   = 256;

// DRY ("don't repeat yourself") targets repeated *phrases/sentences* rather than individual
// tokens - the classic repeat penalty above only discourages reusing the same tokens, so a model
// can still loop the same sentence structure or catchphrase turn after turn with a fresh token
// mix each time. dry_penalty_last_n is a fixed-size ring buffer (its cost per generated token
// does not grow with conversation length), kept modest since it's paid on every single token on
// phone-class CPUs; 0.8x multiplier and the library's own defaults for the rest are the commonly
// used settings for creative writing/roleplay.
constexpr float SAMPLER_DRY_MULTIPLIER   = 0.8f;
constexpr int   SAMPLER_DRY_LAST_N       = 512;

/**
 * Rolling-summary memory: when the context fills up, older messages are condensed into a short
 * summary (via a short, isolated generation) instead of being silently dropped, so identity and
 * plot facts survive far longer than the raw context window would otherwise allow - this is what
 * actually gives an unbounded "remembers the whole conversation" guarantee, since any fixed raw
 * context eventually fills up no matter how large.
 *
 * Re-enabled: this was disabled earlier over a suspected OOM risk from the second llama_context
 * it allocates (its own KV-cache, batch and sampler) on top of the main one. That was never
 * actually confirmed - the real cause of the crashes chased at the time turned out to be a
 * separate KV-cache position-tracking bug (since fixed), unrelated to summarization. Re-enabling
 * with SUMMARY_CONTEXT_SIZE cut way down from the original 4096: summarizing a handful of evicted
 * messages into a few hundred tokens never needed anywhere near that much context, so this keeps
 * the second context's memory footprint small regardless of how big SUMMARY_MAX_NEW_TOKENS gets.
 */
constexpr bool  ENABLE_ROLLING_SUMMARY     = true;
constexpr int   SUMMARY_CONTEXT_SIZE       = 1536;
constexpr int   SUMMARY_MAX_NEW_TOKENS     = 400;
constexpr float SUMMARY_TEMP               = 0.3f;
constexpr int   MIN_MESSAGES_TO_SUMMARIZE  = 2;

static llama_model                      * g_model;
static llama_context                    * g_context;
static llama_batch                        g_batch;
static common_chat_templates_ptr          g_chat_templates;
static common_sampler                   * g_sampler;

// Some models wrap their reply in a "thinking"/reasoning section before the actual in-character
// answer (OpenAI harmony-style <|channel|> markers, generic <think>...</think> tags, etc, per
// whatever the model's own chat template declares) - built from a one-time probe of the template
// in prepare(). When ready, generateNextToken() uses it to show/store only the final answer,
// instead of the raw reasoning text leaking into the roleplay. Best-effort: stays false if the
// probe fails, in which case generation falls back to raw passthrough exactly as before.
static common_chat_parser_params          g_chat_parser_params;
static bool                               g_chat_parser_ready = false;

/**
 * Best-effort native crash diagnostics. On-device native crashes (SIGSEGV/SIGABRT/etc) leave no
 * Kotlin-catchable exception and no logcat access without a PC/adb, so there's otherwise no way
 * for a non-technical user to report what actually happened. This installs a signal handler that
 * appends the signal, the last high-level operation in progress, and memory stats to a plain text
 * file under the app's own storage, then lets the crash proceed normally. Note this cannot catch
 * SIGKILL: if the OS OOM-killer terminates the process under memory pressure, no signal is
 * delivered at all and no log entry is written - a crash that reproduces but never leaves a log
 * entry is itself evidence pointing at an OOM kill rather than a native bug.
 */
static char g_crash_log_path[512] = {0};
static const char *volatile g_last_operation = "startup";

// Message of the last caught C++ exception, surfaced to Kotlin via getLastErrorNative() so an
// error result can be reported with its real cause instead of just a bare numeric code.
static std::string g_last_error;

static void write_proc_file_matching(FILE *out, const char *proc_path, const char *prefix, int max_lines) {
    FILE *in = fopen(proc_path, "r");
    if (!in) { return; }
    char line[256];
    int lines = 0;
    while (fgets(line, sizeof(line), in) && lines < max_lines) {
        if (!prefix || strncmp(line, prefix, strlen(prefix)) == 0) {
            fputs(line, out);
            lines++;
        }
    }
    fclose(in);
}

static void crash_signal_handler(int sig, siginfo_t *info, void * /*ucontext*/) {
    FILE *f = fopen(g_crash_log_path, "a");
    if (f) {
        const time_t now = time(nullptr);
        fprintf(f, "=== CharChat native crash ===\n");
        fprintf(f, "time: %ld\n", (long) now);
        fprintf(f, "signal: %d (%s)\n", sig, strsignal(sig));
        fprintf(f, "fault addr: %p\n", info ? info->si_addr : nullptr);
        fprintf(f, "last operation: %s\n", g_last_operation);
        write_proc_file_matching(f, "/proc/self/status", "Vm", 10);
        write_proc_file_matching(f, "/proc/meminfo", nullptr, 3);
        fprintf(f, "==============================\n\n");
        fclose(f);
    }
    // Restore the default handler and re-raise so the process still terminates normally.
    signal(sig, SIG_DFL);
    raise(sig);
}

static void install_crash_handler(const char *path) {
    strncpy(g_crash_log_path, path, sizeof(g_crash_log_path) - 1);
    struct sigaction sa{};
    sa.sa_sigaction = crash_signal_handler;
    sa.sa_flags = SA_SIGINFO;
    sigemptyset(&sa.sa_mask);
    sigaction(SIGSEGV, &sa, nullptr);
    sigaction(SIGABRT, &sa, nullptr);
    sigaction(SIGBUS, &sa, nullptr);
    sigaction(SIGILL, &sa, nullptr);
    sigaction(SIGFPE, &sa, nullptr);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_init(
        JNIEnv *env,
        jobject /*unused*/,
        jstring nativeLibDir,
        jstring crashLogPath
) {
    const auto *crash_log_path = env->GetStringUTFChars(crashLogPath, 0);
    install_crash_handler(crash_log_path);
    env->ReleaseStringUTFChars(crashLogPath, crash_log_path);

    // Set llama log handler to Android
    llama_log_set(aichat_android_log_callback, nullptr);

    // Loading all CPU backend variants
    const auto *path_to_backend = env->GetStringUTFChars(nativeLibDir, 0);
    LOGi("Loading backends from %s", path_to_backend);
    ggml_backend_load_all_from_path(path_to_backend);
    env->ReleaseStringUTFChars(nativeLibDir, path_to_backend);

    // Initialize backends
    llama_backend_init();
    LOGi("Backend initiated; Log handler set.");
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_getLastErrorNative(JNIEnv *env, jobject /*unused*/) {
    return env->NewStringUTF(g_last_error.c_str());
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_load(JNIEnv *env, jobject, jstring jmodel_path) try {
    g_last_operation = "load(model weights)";
    llama_model_params model_params = llama_model_default_params();

    const auto *model_path = env->GetStringUTFChars(jmodel_path, 0);
    LOGd("%s: Loading model from: \n%s\n", __func__, model_path);

    auto *model = llama_model_load_from_file(model_path, model_params);
    env->ReleaseStringUTFChars(jmodel_path, model_path);
    if (!model) {
        return 1;
    }
    g_model = model;
    return 0;
} catch (const std::exception &e) {
    LOGe("%s: uncaught exception: %s", __func__, e.what());
    g_last_error = e.what();
    return 99;
} catch (...) {
    LOGe("%s: uncaught non-std exception", __func__);
    g_last_error = "unknown non-standard exception";
    return 98;
}

static llama_context *init_context(llama_model *model, const int n_ctx = DEFAULT_CONTEXT_SIZE) {
    g_last_operation = "init_context";
    if (!model) {
        LOGe("%s: model cannot be null", __func__);
        return nullptr;
    }

    // Multi-threading setup
    const int n_threads = std::max(N_THREADS_MIN, std::min(N_THREADS_MAX,
                                                     (int) sysconf(_SC_NPROCESSORS_ONLN) -
                                                     N_THREADS_HEADROOM));
    LOGi("%s: Using %d threads", __func__, n_threads);

    // Context parameters setup
    llama_context_params ctx_params = llama_context_default_params();
    const int trained_context_size = llama_model_n_ctx_train(model);
    if (n_ctx > trained_context_size) {
        LOGw("%s: Model was trained with only %d context size! Enforcing %d context size...",
             __func__, trained_context_size, n_ctx);
    }
    ctx_params.n_ctx = n_ctx;
    ctx_params.n_batch = BATCH_SIZE;
    ctx_params.n_ubatch = BATCH_SIZE;
    ctx_params.n_threads = n_threads;
    ctx_params.n_threads_batch = n_threads;
    auto *context = llama_init_from_model(g_model, ctx_params);
    if (context == nullptr) {
        LOGe("%s: llama_new_context_with_model() returned null)", __func__);
    }
    return context;
}

static common_sampler *new_sampler(float temp) {
    common_params_sampling sparams;
    sparams.temp = temp;
    sparams.penalty_repeat = SAMPLER_REPEAT_PENALTY;
    sparams.penalty_last_n = SAMPLER_REPEAT_LAST_N;
    sparams.dry_multiplier = SAMPLER_DRY_MULTIPLIER;
    sparams.dry_penalty_last_n = SAMPLER_DRY_LAST_N;
    return common_sampler_init(g_model, sparams);
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_prepare(JNIEnv * /*env*/, jobject /*unused*/) try {
    auto *context = init_context(g_model);
    if (!context) { return 1; }
    g_context = context;
    g_batch = llama_batch_init(BATCH_SIZE, 0, 1);
    g_chat_templates = common_chat_templates_init(g_model, "");
    g_sampler = new_sampler(DEFAULT_SAMPLER_TEMP);

    // One-time probe of the template so a reasoning-capable model's "thinking" section can be
    // recognized and stripped later (see g_chat_parser_ready above). Deliberately isolated from
    // the outer try/catch: a probe failure must not fail model loading, it should just leave
    // g_chat_parser_ready false and fall back to showing raw text exactly as before.
    try {
        common_chat_templates_inputs probe_inputs;
        common_chat_msg probe_msg;
        probe_msg.role = "user";
        probe_msg.content = "hi";
        probe_inputs.messages = {probe_msg};
        probe_inputs.add_generation_prompt = true;
        probe_inputs.use_jinja = true;
        probe_inputs.reasoning_format = COMMON_REASONING_FORMAT_AUTO;
        probe_inputs.enable_thinking = true;
        const auto probe_params = common_chat_templates_apply(g_chat_templates.get(), probe_inputs);
        g_chat_parser_params = common_chat_parser_params(probe_params);
        g_chat_parser_params.reasoning_format = COMMON_REASONING_FORMAT_AUTO;
        g_chat_parser_params.parser.load(probe_params.parser);
        g_chat_parser_ready = true;
        LOGi("%s: chat parser ready, format=%s", __func__, common_chat_format_name(probe_params.format));
    } catch (const std::exception &e) {
        LOGw("%s: chat parser probe failed, falling back to raw passthrough: %s", __func__, e.what());
        g_chat_parser_ready = false;
    } catch (...) {
        LOGw("%s: chat parser probe failed with a non-std exception, falling back to raw passthrough", __func__);
        g_chat_parser_ready = false;
    }

    return 0;
} catch (const std::exception &e) {
    LOGe("%s: uncaught exception: %s", __func__, e.what());
    g_last_error = e.what();
    return 99;
} catch (...) {
    LOGe("%s: uncaught non-std exception", __func__);
    g_last_error = "unknown non-standard exception";
    return 98;
}

/**
 * Rebuilds the sampler with a new temperature (the user-facing "creativity" slider). Safe to call
 * any time the model is loaded: it only swaps the sampling object, no KV-cache/position state.
 */
extern "C"
JNIEXPORT jint JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_setSamplerTemperatureNative(
        JNIEnv * /*env*/,
        jobject /*unused*/,
        jfloat temp
) {
    if (!g_model) {
        LOGe("%s: model not loaded", __func__);
        return 1;
    }
    auto *sampler = new_sampler(temp);
    if (!sampler) {
        LOGe("%s: failed to create sampler", __func__);
        return 2;
    }
    common_sampler_free(g_sampler);
    g_sampler = sampler;
    return 0;
}

static std::string get_backend() {
    std::vector<std::string> backends;
    for (size_t i = 0; i < ggml_backend_reg_count(); i++) {
        auto *reg = ggml_backend_reg_get(i);
        std::string name = ggml_backend_reg_name(reg);
        if (name != "CPU") {
            backends.push_back(ggml_backend_reg_name(reg));
        }
    }
    return backends.empty() ? "CPU" : join(backends, ",");
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_systemInfo(JNIEnv *env, jobject /*unused*/) {
    return env->NewStringUTF(llama_print_system_info());
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_benchModel(JNIEnv *env, jobject /*unused*/, jint pp, jint tg,
                                                      jint pl, jint nr) {
    auto *context = init_context(g_model, pp);
    if (!context) {
        const auto *const err_msg = "Fail to init_context! Bench aborted.";
        LOGe(err_msg);
        return env->NewStringUTF(err_msg);
    }

    auto pp_avg = 0.0;
    auto tg_avg = 0.0;
    auto pp_std = 0.0;
    auto tg_std = 0.0;

    const uint32_t n_ctx = llama_n_ctx(context);
    LOGi("n_ctx = %d", n_ctx);

    int i, j;
    int nri;
    for (nri = 0; nri < nr; nri++) {
        LOGi("Benchmark prompt processing (pp = %d)", pp);

        common_batch_clear(g_batch);

        const int n_tokens = pp;
        for (i = 0; i < n_tokens; i++) {
            common_batch_add(g_batch, 0, i, {0}, false);
        }

        g_batch.logits[g_batch.n_tokens - 1] = true;
        llama_memory_clear(llama_get_memory(context), false);

        const auto t_pp_start = ggml_time_us();
        if (llama_decode(context, g_batch) != 0) {
            LOGe("llama_decode() failed during prompt processing");
        }
        const auto t_pp_end = ggml_time_us();

        // bench text generation

        LOGi("Benchmark text generation (tg = %d)", tg);

        llama_memory_clear(llama_get_memory(context), false);
        const auto t_tg_start = ggml_time_us();
        for (i = 0; i < tg; i++) {
            common_batch_clear(g_batch);
            for (j = 0; j < pl; j++) {
                common_batch_add(g_batch, 0, i, {j}, true);
            }

            if (llama_decode(context, g_batch) != 0) {
                LOGe("llama_decode() failed during text generation");
            }
        }
        const auto t_tg_end = ggml_time_us();

        llama_memory_clear(llama_get_memory(context), false);

        const auto t_pp = double(t_pp_end - t_pp_start) / 1000000.0;
        const auto t_tg = double(t_tg_end - t_tg_start) / 1000000.0;

        const auto speed_pp = double(pp) / t_pp;
        const auto speed_tg = double(pl * tg) / t_tg;

        pp_avg += speed_pp;
        tg_avg += speed_tg;

        pp_std += speed_pp * speed_pp;
        tg_std += speed_tg * speed_tg;

        LOGi("pp %f t/s, tg %f t/s", speed_pp, speed_tg);
    }

    llama_free(context);

    pp_avg /= double(nr);
    tg_avg /= double(nr);

    if (nr > 1) {
        pp_std = sqrt(pp_std / double(nr - 1) - pp_avg * pp_avg * double(nr) / double(nr - 1));
        tg_std = sqrt(tg_std / double(nr - 1) - tg_avg * tg_avg * double(nr) / double(nr - 1));
    } else {
        pp_std = 0;
        tg_std = 0;
    }

    char model_desc[128];
    llama_model_desc(g_model, model_desc, sizeof(model_desc));

    const auto model_size = double(llama_model_size(g_model)) / 1024.0 / 1024.0 / 1024.0;
    const auto model_n_params = double(llama_model_n_params(g_model)) / 1e9;

    const auto backend = get_backend();
    std::stringstream result;
    result << std::setprecision(3);
    result << "| model | size | params | backend | test | t/s |\n";
    result << "| --- | --- | --- | --- | --- | --- |\n";
    result << "| " << model_desc << " | " << model_size << "GiB | " << model_n_params << "B | "
           << backend << " | pp " << pp << " | " << pp_avg << " ± " << pp_std << " |\n";
    result << "| " << model_desc << " | " << model_size << "GiB | " << model_n_params << "B | "
           << backend << " | tg " << tg << " | " << tg_avg << " ± " << tg_std << " |\n";
    return env->NewStringUTF(result.str().c_str());
}


/**
 * Completion loop's long-term states:
 * - chat management
 * - position tracking
 */
constexpr const char *ROLE_SYSTEM       = "system";
constexpr const char *ROLE_USER         = "user";
constexpr const char *ROLE_ASSISTANT    = "assistant";

static std::vector<common_chat_msg> chat_msgs;
// End token position of each chat_msgs entry (same length as chat_msgs, or shorter mid-update).
// chat_msgs[0] is always the pinned system/persona message and is never summarized or evicted.
static std::vector<llama_pos> chat_msg_end_positions;
static llama_pos system_prompt_position;
static llama_pos current_position;

static void reset_long_term_states(const bool clear_kv_cache = true) {
    chat_msgs.clear();
    chat_msg_end_positions.clear();
    system_prompt_position = 0;
    current_position = 0;

    if (clear_kv_cache)
        llama_memory_clear(llama_get_memory(g_context), false);
}

static void mark_message_end(const llama_pos pos) {
    if (chat_msg_end_positions.size() < chat_msgs.size()) {
        chat_msg_end_positions.push_back(pos);
    }
}

static std::string chat_add_and_format(const std::string &role, const std::string &content) {
    common_chat_msg new_msg;
    new_msg.role = role;
    new_msg.content = content;
    // use_jinja=true: the legacy (non-jinja) path only recognizes a small hardcoded set of known
    // chat templates and throws ("this custom template is not supported, try using --jinja") on
    // anything else, which aborted every single conversation on GGUF models whose baked-in chat
    // template isn't one of those. The full jinja interpreter handles any valid template the
    // model actually ships, which is what's needed for arbitrary user-picked GGUFs.
    auto formatted = common_chat_format_single(
            g_chat_templates.get(), chat_msgs, new_msg, role == ROLE_USER, /* use_jinja */ true);
    chat_msgs.push_back(new_msg);
    LOGi("%s: Formatted and added %s message: \n%s\n", __func__, role.c_str(), formatted.c_str());
    return formatted;
}

static int decode_tokens_in_batches(
        llama_context *context,
        llama_batch &batch,
        const llama_tokens &tokens,
        llama_pos start_pos,
        bool compute_last_logit);

/**
 * Condenses the oldest messages about to be evicted (everything up to [cutoff], excluding the
 * pinned system message) into a short summary via a short, isolated generation on a separate
 * temporary context, then injects that summary as a synthetic system note at the current tail.
 * The note itself becomes part of the "kept" window and will be folded into a future summary
 * once it eventually ages out too, so the digest keeps compounding instead of ever fully
 * disappearing. Fails safe: on any error this simply does nothing, leaving the plain discard
 * that follows in shift_context() as the only effect.
 */
static std::string summarize_messages(const std::vector<common_chat_msg> &to_summarize) {
    if (to_summarize.empty()) {
        return "";
    }

    std::ostringstream transcript;
    for (const auto &msg: to_summarize) {
        transcript << msg.role << ": " << msg.content << "\n";
    }

    const std::string prompt =
            "Summarize the roleplay conversation below in 6-9 short sentences. "
            "Keep character names, relationships, locations, and important facts, decisions, or "
            "events - be specific rather than vague, since this summary is the only memory of "
            "this part of the conversation going forward. Also note anything that shows how a "
            "character actually behaves or talks (a distinctive reaction, attitude shift, or "
            "running dynamic between characters), not just what happened, so their voice stays "
            "consistent later. "
            "Do not add any commentary, only output the summary itself.\n\n"
            + transcript.str() + "\nSummary:";

    auto *summary_context = init_context(g_model, SUMMARY_CONTEXT_SIZE);
    if (!summary_context) {
        LOGe("%s: failed to create a temporary context for summarization", __func__);
        return "";
    }

    auto tokens = common_tokenize(summary_context, prompt, true, true);
    const int max_prompt_tokens = SUMMARY_CONTEXT_SIZE - SUMMARY_MAX_NEW_TOKENS - OVERFLOW_HEADROOM;
    if ((int) tokens.size() > max_prompt_tokens && max_prompt_tokens > 0) {
        // Keep the most recent portion; the tail of the conversation matters most for context.
        tokens.erase(tokens.begin(), tokens.end() - max_prompt_tokens);
    }

    llama_batch summary_batch = llama_batch_init(std::max((int) tokens.size(), SUMMARY_MAX_NEW_TOKENS) + 8, 0, 1);
    std::string result;

    if (decode_tokens_in_batches(summary_context, summary_batch, tokens, 0, true)) {
        LOGe("%s: prompt decode failed", __func__);
    } else {
        common_params_sampling sparams;
        sparams.temp = SUMMARY_TEMP;
        auto *summary_sampler = common_sampler_init(g_model, sparams);

        llama_pos pos = (llama_pos) tokens.size();
        for (int i = 0; i < SUMMARY_MAX_NEW_TOKENS; i++) {
            const auto new_token = common_sampler_sample(summary_sampler, summary_context, -1);
            common_sampler_accept(summary_sampler, new_token, true);
            if (llama_vocab_is_eog(llama_model_get_vocab(g_model), new_token)) {
                break;
            }
            result += common_token_to_piece(summary_context, new_token);

            common_batch_clear(summary_batch);
            common_batch_add(summary_batch, new_token, pos, {0}, true);
            if (llama_decode(summary_context, summary_batch) != 0) {
                LOGe("%s: decode failed mid-generation", __func__);
                break;
            }
            pos++;
        }
        common_sampler_free(summary_sampler);
    }

    llama_batch_free(summary_batch);
    llama_free(summary_context);

    LOGi("%s: produced summary: \n%s", __func__, result.c_str());
    return result;
}

// Guards against re-entrant compaction: injecting the summary note below can itself land close
// enough to the limit to trigger another shift_context() -> compact_history_before_shift() call
// before this one has finished. If that happens, just skip the inner one and let the plain
// discard proceed instead of cascading.
static bool g_compacting_history = false;

static void compact_history_before_shift(const int n_discard) {
    if (g_compacting_history || chat_msgs.size() < 2) {
        return; // nothing beyond the pinned system message yet, or already compacting
    }

    const llama_pos cutoff = system_prompt_position + n_discard;

    // Start at 1: chat_msgs[0] is always the pinned system/persona message.
    size_t evict_count = 1;
    while (evict_count < chat_msg_end_positions.size() && chat_msg_end_positions[evict_count] <= cutoff) {
        evict_count++;
    }
    if (evict_count - 1 < MIN_MESSAGES_TO_SUMMARIZE) {
        return;
    }

    g_compacting_history = true;

    const std::vector<common_chat_msg> to_summarize(chat_msgs.begin() + 1, chat_msgs.begin() + (long) evict_count);
    const std::string summary = summarize_messages(to_summarize);
    if (summary.empty()) {
        g_compacting_history = false;
        return;
    }

    const std::string note = "[Recap of earlier events, for reference: " + summary + "]";
    const bool has_chat_template = common_chat_templates_was_explicit(g_chat_templates.get());
    std::string formatted = note;
    if (has_chat_template) {
        formatted = chat_add_and_format(ROLE_SYSTEM, note);
    }
    const auto tokens = common_tokenize(g_context, formatted, has_chat_template, has_chat_template);

    // Uses its own batch (not the shared g_batch), since this can run while g_batch is already
    // mid-use by the decode_tokens_in_batches() call that triggered this shift in the first place.
    llama_batch note_batch = llama_batch_init(std::max((int) tokens.size(), 1), 0, 1);
    const int decode_failed = decode_tokens_in_batches(g_context, note_batch, tokens, current_position, false);
    llama_batch_free(note_batch);

    g_compacting_history = false;

    if (decode_failed) {
        LOGe("%s: failed to inject summary note", __func__);
        return;
    }

    current_position += (int) tokens.size();
    mark_message_end(current_position);

    // Collapse the summarized messages out of the logical history so future rounds don't
    // re-summarize content that's already gone from the KV-cache.
    chat_msgs.erase(chat_msgs.begin() + 1, chat_msgs.begin() + (long) evict_count);
    chat_msg_end_positions.erase(chat_msg_end_positions.begin() + 1, chat_msg_end_positions.begin() + (long) evict_count);

    LOGi("%s: compacted %d old messages into a summary", __func__, (int) evict_count - 1);
}

/**
 * Context shifting by discarding the oldest quarter of the tokens appended after the system
 * prompt, after first folding them into a rolling summary (see compact_history_before_shift)
 * so the eviction loses as little as possible:
 * - take the [system_prompt_position] first tokens from the original prompt
 * - take a quarter of the tokens that follow
 * - recompute the logits in batches
 */
static int shift_context() {
    const int n_discard = (current_position - system_prompt_position) / 4;
    if (n_discard <= 0) {
        return 0;
    }
    g_last_operation = "shift_context";
    LOGi("%s: Discarding %d tokens", __func__, n_discard);

    if (ENABLE_ROLLING_SUMMARY) {
        compact_history_before_shift(n_discard);
    }

    llama_memory_seq_rm(llama_get_memory(g_context), 0, system_prompt_position, system_prompt_position + n_discard);
    llama_memory_seq_add(llama_get_memory(g_context), 0, system_prompt_position + n_discard, current_position, -n_discard);
    current_position -= n_discard;
    LOGi("%s: Context shifting done! Current position: %d", __func__, current_position);
    return n_discard;
}

/**
 * Completion loop's short-term states:
 * - stop generation position
 * - token chars caching
 * - raw text generated so far this turn (only parsed once, at the end - see
 *   current_visible_content() and finalize_assistant_turn())
 * - timing/count stats for the turn, so actual speed can be surfaced to the user instead of
 *   guessed at (see getLastReplyStatsNative())
 */
static llama_pos   stop_generation_position;
static std::string cached_token_chars;
static std::string g_raw_generated;
static bool         g_parse_failed_this_turn;
static int64_t      g_generation_start_us;
static int          g_generated_token_count;

static void reset_short_term_states() {
    stop_generation_position = 0;
    cached_token_chars.clear();
    g_raw_generated.clear();
    g_parse_failed_this_turn = false;
    g_generation_start_us = ggml_time_us();
    g_generated_token_count = 0;
}

/**
 * Returns just the user-facing reply generated so far this turn, with any "thinking"/reasoning
 * section the model's chat template defines stripped out (see g_chat_parser_ready above). Falls
 * back to the raw text unchanged if the parser isn't available, or the moment it fails once for
 * this turn (rather than retrying and re-throwing on every remaining token), reproducing the old
 * raw-passthrough behavior exactly.
 */
static std::string current_visible_content(bool is_partial) {
    if (!g_chat_parser_ready || g_parse_failed_this_turn) {
        return g_raw_generated;
    }
    try {
        return common_chat_parse(g_raw_generated, is_partial, g_chat_parser_params).content;
    } catch (const std::exception &e) {
        LOGw("%s: chat parse failed, falling back to raw text for the rest of this reply: %s", __func__, e.what());
        g_parse_failed_this_turn = true;
        return g_raw_generated;
    } catch (...) {
        LOGw("%s: chat parse failed with a non-std exception, falling back to raw text for the rest of this reply",
             __func__);
        g_parse_failed_this_turn = true;
        return g_raw_generated;
    }
}

// Last completed reply's clean text and a human-readable speed summary, surfaced to Kotlin via
// getLastReplyNative()/getLastReplyStatsNative() so actual generation speed can be shown to the
// user instead of guessed at from the outside.
static std::string g_last_reply;
static std::string g_last_reply_stats;

/**
 * Finalizes the assistant's turn regardless of *why* generation stopped (natural end-of-message,
 * or hitting the token-length cap): stores the clean reply into chat history and marks its end
 * position. Without this, a reply cut short by the length cap would never get recorded, leaving
 * future turns' chat-template rendering silently missing this message. The reasoning-aware parse
 * only runs once here, at the end, rather than on every token during generation - nothing is
 * shown to the user until the reply is complete anyway (see ChatActivity's thinking indicator),
 * so re-parsing the whole growing reply on every single token was pure wasted CPU time that
 * only got more expensive the longer a reply ran.
 */
static void finalize_assistant_turn(const char *reason) {
    const double elapsed_s = (double) (ggml_time_us() - g_generation_start_us) / 1e6;
    const double tok_per_s = elapsed_s > 0 ? g_generated_token_count / elapsed_s : 0.0;
    LOGw("%s: STOP: %s (%d tokens in %.1fs, %.2f tok/s)",
         __func__, reason, g_generated_token_count, elapsed_s, tok_per_s);

    g_last_reply = current_visible_content(/* is_partial */ false);
    std::ostringstream stats;
    stats << g_generated_token_count << " tokens in " << std::fixed << std::setprecision(1) << elapsed_s
          << "s (" << std::setprecision(2) << tok_per_s << " tok/s)";
    g_last_reply_stats = stats.str();

    chat_add_and_format(ROLE_ASSISTANT, g_last_reply);
    mark_message_end(current_position);
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_getLastReplyNative(JNIEnv *env, jobject /*unused*/) {
    return env->NewStringUTF(g_last_reply.c_str());
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_getLastReplyStatsNative(JNIEnv *env, jobject /*unused*/) {
    return env->NewStringUTF(g_last_reply_stats.c_str());
}

/**
 * The model's current chat history after the pinned system message (chat_msgs[0]) - any
 * rolling-summary recap notes folded in by context shifting, plus the still-live raw turns - as
 * a JSON array of {"role", "content"} objects, in order. Lets the Kotlin side snapshot a bounded
 * picture of native memory (never larger than what actually still fits in context) so a later
 * cold-start replay can resume from this instead of always re-seeding the entire, ever-growing
 * raw transcript from scratch. "[]" if no system prompt has been processed yet.
 */
extern "C"
JNIEXPORT jstring JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_getCompactedHistoryNative(JNIEnv *env, jobject /*unused*/) {
    std::ostringstream out;
    out << "[";
    for (size_t i = 1; i < chat_msgs.size(); i++) {
        if (i > 1) out << ",";
        out << "{\"role\":\"" << json_escape(chat_msgs[i].role) << "\","
            << "\"content\":\"" << json_escape(chat_msgs[i].content) << "\"}";
    }
    out << "]";
    return env->NewStringUTF(out.str().c_str());
}

static int decode_tokens_in_batches(
        llama_context *context,
        llama_batch &batch,
        const llama_tokens &tokens,
        llama_pos start_pos,
        const bool compute_last_logit = false) {
    // Process tokens in batches using the global batch
    LOGd("%s: Decode %d tokens starting at position %d", __func__, (int) tokens.size(), start_pos);
    for (int i = 0; i < (int) tokens.size(); i += BATCH_SIZE) {
        const int cur_batch_size = std::min((int) tokens.size() - i, BATCH_SIZE);
        common_batch_clear(batch);
        LOGv("%s: Preparing a batch size of %d starting at: %d", __func__, cur_batch_size, i);

        // Shift context if current batch cannot fit into the context. shift_context() only ever
        // operates on g_context/current_position, so it must never run while decoding into some
        // other context (e.g. the isolated summary context) - and when it does run, it physically
        // remaps the KV-cache and moves current_position backwards, so start_pos (this call's own
        // snapshot of where to place tokens) must shift by the exact same amount, or every
        // remaining token in this call - and every call after it - gets written to a stale, wrong
        // position that silently drifts further from the KV-cache's real occupancy until writes
        // land past the context bound and crash.
        if (context == g_context &&
            start_pos + i + cur_batch_size >= DEFAULT_CONTEXT_SIZE - OVERFLOW_HEADROOM) {
            LOGw("%s: Current batch won't fit into context! Shifting...", __func__);
            start_pos -= shift_context();
        }

        // Add tokens to the batch with proper positions
        for (int j = 0; j < cur_batch_size; j++) {
            const llama_token token_id = tokens[i + j];
            const llama_pos position = start_pos + i + j;
            const bool want_logit = compute_last_logit && (i + j == tokens.size() - 1);
            common_batch_add(batch, token_id, position, {0}, want_logit);
        }

        // Decode this batch
        const int decode_result = llama_decode(context, batch);
        if (decode_result) {
            LOGe("%s: llama_decode failed w/ %d", __func__, decode_result);
            return 1;
        }
    }
    return 0;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_processSystemPrompt(
        JNIEnv *env,
        jobject /*unused*/,
        jstring jsystem_prompt
) try {
    g_last_operation = "processSystemPrompt";
    // Reset long-term & short-term states
    reset_long_term_states();
    reset_short_term_states();

    // Obtain system prompt from JEnv
    const auto *system_prompt = env->GetStringUTFChars(jsystem_prompt, nullptr);
    LOGd("%s: System prompt received: \n%s", __func__, system_prompt);
    std::string formatted_system_prompt(system_prompt);

    // Format system prompt if applicable
    const bool has_chat_template = common_chat_templates_was_explicit(g_chat_templates.get());
    if (has_chat_template) {
        formatted_system_prompt = chat_add_and_format(ROLE_SYSTEM, system_prompt);
    }
    env->ReleaseStringUTFChars(jsystem_prompt, system_prompt);

    // Tokenize system prompt
    const auto system_tokens = common_tokenize(g_context, formatted_system_prompt,
                                               has_chat_template, has_chat_template);
    for (auto id: system_tokens) {
        LOGv("token: `%s`\t -> `%d`", common_token_to_piece(g_context, id).c_str(), id);
    }

    // Handle context overflow
    const int max_batch_size = DEFAULT_CONTEXT_SIZE - OVERFLOW_HEADROOM;
    if ((int) system_tokens.size() > max_batch_size) {
        LOGe("%s: System prompt too long for context! %d tokens, max: %d",
             __func__, (int) system_tokens.size(), max_batch_size);
        return 1;
    }

    // Decode system tokens in batches
    if (decode_tokens_in_batches(g_context, g_batch, system_tokens, current_position)) {
        LOGe("%s: llama_decode() failed!", __func__);
        return 2;
    }

    // Update position
    system_prompt_position = current_position = (int) system_tokens.size();
    mark_message_end(current_position);
    return 0;
} catch (const std::exception &e) {
    // An uncaught C++ exception crossing back into the JVM aborts the whole process with no
    // catchable Kotlin exception and no clear signal of why - turn it into an ordinary error
    // result instead, which the Kotlin side already surfaces as a recoverable failure.
    LOGe("%s: uncaught exception: %s", __func__, e.what());
    g_last_error = e.what();
    return 99;
} catch (...) {
    LOGe("%s: uncaught non-std exception", __func__);
    g_last_error = "unknown non-standard exception";
    return 98;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_processUserPrompt(
        JNIEnv *env,
        jobject /*unused*/,
        jstring juser_prompt,
        jint n_predict
) try {
    g_last_operation = "processUserPrompt";
    // Reset short-term states
    reset_short_term_states();

    // Obtain and tokenize user prompt
    const auto *const user_prompt = env->GetStringUTFChars(juser_prompt, nullptr);
    LOGd("%s: User prompt received: \n%s", __func__, user_prompt);
    std::string formatted_user_prompt(user_prompt);

    // Format user prompt if applicable
    const bool has_chat_template = common_chat_templates_was_explicit(g_chat_templates.get());
    if (has_chat_template) {
        formatted_user_prompt = chat_add_and_format(ROLE_USER, user_prompt);
    }
    env->ReleaseStringUTFChars(juser_prompt, user_prompt);

    // Decode formatted user prompts
    auto user_tokens = common_tokenize(g_context, formatted_user_prompt, has_chat_template, has_chat_template);
    for (auto id: user_tokens) {
        LOGv("token: `%s`\t -> `%d`", common_token_to_piece(g_context, id).c_str(), id);
    }

    // Ensure user prompt doesn't exceed the context size by truncating if necessary.
    const int user_prompt_size = (int) user_tokens.size();
    const int max_batch_size = DEFAULT_CONTEXT_SIZE - OVERFLOW_HEADROOM;
    if (user_prompt_size > max_batch_size) {
        const int skipped_tokens = user_prompt_size - max_batch_size;
        user_tokens.resize(max_batch_size);
        LOGw("%s: User prompt too long! Skipped %d tokens!", __func__, skipped_tokens);
    }

    // Decode user tokens in batches
    if (decode_tokens_in_batches(g_context, g_batch, user_tokens, current_position, true)) {
        LOGe("%s: llama_decode() failed!", __func__);
        return 2;
    }

    // Update position
    current_position += user_prompt_size;
    mark_message_end(current_position);
    // current_position already includes user_prompt_size (just added above) - the reply's token
    // budget is n_predict tokens from here, not n_predict + another full copy of the prompt size.
    stop_generation_position = current_position + n_predict;
    return 0;
} catch (const std::exception &e) {
    LOGe("%s: uncaught exception: %s", __func__, e.what());
    g_last_error = e.what();
    return 99;
} catch (...) {
    LOGe("%s: uncaught non-std exception", __func__);
    g_last_error = "unknown non-standard exception";
    return 98;
}

/**
 * Injects a canned turn (user or assistant) directly into the KV-cache and chat history, without
 * generating it. Used both for the character's opening greeting and for replaying a persisted
 * conversation after the app restarts, so the model's actual memory is rebuilt, not just the UI.
 */
static int seed_message(const std::string &role, const std::string &text) try {
    g_last_operation = (role == ROLE_USER) ? "seed_message(user)"
                        : (role == ROLE_ASSISTANT) ? "seed_message(assistant)"
                        : "seed_message(system)";
    reset_short_term_states();

    std::string formatted_text = text;
    const bool has_chat_template = common_chat_templates_was_explicit(g_chat_templates.get());
    if (has_chat_template) {
        formatted_text = chat_add_and_format(role, text);
    }

    const auto tokens = common_tokenize(g_context, formatted_text, has_chat_template, has_chat_template);

    const int max_batch_size = DEFAULT_CONTEXT_SIZE - OVERFLOW_HEADROOM;
    if ((int) tokens.size() > max_batch_size) {
        LOGe("%s: Seed message too long for context! %d tokens, max: %d",
             __func__, (int) tokens.size(), max_batch_size);
        return 1;
    }

    if (decode_tokens_in_batches(g_context, g_batch, tokens, current_position)) {
        LOGe("%s: llama_decode() failed!", __func__);
        return 2;
    }

    current_position += (int) tokens.size();
    mark_message_end(current_position);
    return 0;
} catch (const std::exception &e) {
    LOGe("%s: uncaught exception: %s", __func__, e.what());
    g_last_error = e.what();
    return 99;
} catch (...) {
    LOGe("%s: uncaught non-std exception", __func__);
    g_last_error = "unknown non-standard exception";
    return 98;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_seedAssistantMessageNative(
        JNIEnv *env,
        jobject /*unused*/,
        jstring jtext
) {
    const auto *const text = env->GetStringUTFChars(jtext, nullptr);
    LOGd("%s: Seeding assistant message: \n%s", __func__, text);
    const std::string content(text);
    env->ReleaseStringUTFChars(jtext, text);
    return seed_message(ROLE_ASSISTANT, content);
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_seedUserMessageNative(
        JNIEnv *env,
        jobject /*unused*/,
        jstring jtext
) {
    const auto *const text = env->GetStringUTFChars(jtext, nullptr);
    LOGd("%s: Seeding user message: \n%s", __func__, text);
    const std::string content(text);
    env->ReleaseStringUTFChars(jtext, text);
    return seed_message(ROLE_USER, content);
}

/**
 * Injects a system-role note (e.g. a saved rolling-summary recap from [getCompactedHistoryNative])
 * into context/history without generating it - the mid-conversation counterpart to
 * processSystemPrompt(), which only ever sets the pinned persona message at chat_msgs[0].
 */
extern "C"
JNIEXPORT jint JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_seedSystemNoteNative(
        JNIEnv *env,
        jobject /*unused*/,
        jstring jtext
) {
    const auto *const text = env->GetStringUTFChars(jtext, nullptr);
    LOGd("%s: Seeding system note: \n%s", __func__, text);
    const std::string content(text);
    env->ReleaseStringUTFChars(jtext, text);
    return seed_message(ROLE_SYSTEM, content);
}

static bool is_valid_utf8(const char *string) {
    if (!string) { return true; }

    const auto *bytes = (const unsigned char *) string;
    int num;

    while (*bytes != 0x00) {
        if ((*bytes & 0x80) == 0x00) {
            // U+0000 to U+007F
            num = 1;
        } else if ((*bytes & 0xE0) == 0xC0) {
            // U+0080 to U+07FF
            num = 2;
        } else if ((*bytes & 0xF0) == 0xE0) {
            // U+0800 to U+FFFF
            num = 3;
        } else if ((*bytes & 0xF8) == 0xF0) {
            // U+10000 to U+10FFFF
            num = 4;
        } else {
            return false;
        }

        bytes += 1;
        for (int i = 1; i < num; ++i) {
            if ((*bytes & 0xC0) != 0x80) {
                return false;
            }
            bytes += 1;
        }
    }
    return true;
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_generateNextToken(
        JNIEnv *env,
        jobject /*unused*/
) try {
    g_last_operation = "generateNextToken";
    // Infinite text generation via context shifting
    if (current_position >= DEFAULT_CONTEXT_SIZE - OVERFLOW_HEADROOM) {
        LOGw("%s: Context full! Shifting...", __func__);
        shift_context();
    }

    // Stop if reaching the marked position
    if (current_position >= stop_generation_position) {
        finalize_assistant_turn("hit the token-length cap");
        return nullptr;
    }

    // Sample next token
    const auto new_token_id = common_sampler_sample(g_sampler, g_context, -1);
    common_sampler_accept(g_sampler, new_token_id, true);

    // Populate the batch with new token, then decode
    common_batch_clear(g_batch);
    common_batch_add(g_batch, new_token_id, current_position, {0}, true);
    if (llama_decode(g_context, g_batch) != 0) {
        LOGe("%s: llama_decode() failed for generated token", __func__);
        return nullptr;
    }

    // Update position
    current_position++;

    // Stop if next token is EOG
    if (llama_vocab_is_eog(llama_model_get_vocab(g_model), new_token_id)) {
        finalize_assistant_turn("reached a natural end-of-message");
        return nullptr;
    }

    // If not EOG, convert to text. special=true: any reasoning/channel markers the model's format
    // uses need to stay visible in the raw buffer so current_visible_content() can recognize and
    // strip them - only the parsed, user-facing content actually gets streamed out below.
    auto new_token_chars = common_token_to_piece(g_context, new_token_id, /* special */ true);
    cached_token_chars += new_token_chars;

    // Wait for a valid UTF-8 boundary before appending to the turn's raw buffer or parsing it.
    if (!is_valid_utf8(cached_token_chars.c_str())) {
        LOGv("id: %d,\tappend to cache", new_token_id);
        return env->NewStringUTF("");
    }
    LOGv("id: %d,\tcached: `%s`,\tnew: `%s`", new_token_id, cached_token_chars.c_str(), new_token_chars.c_str());
    g_raw_generated += cached_token_chars;
    cached_token_chars.clear();
    g_generated_token_count++;

    // Nothing is shown to the user until the reply is complete (see ChatActivity's thinking
    // indicator), so there's no reason to pay for re-parsing the whole growing reply on every
    // token here - that only happens once, in finalize_assistant_turn(), when it actually matters.
    return env->NewStringUTF("");
} catch (const std::exception &e) {
    LOGe("%s: uncaught exception: %s", __func__, e.what());
    g_last_error = e.what();
    return nullptr;
} catch (...) {
    LOGe("%s: uncaught non-std exception", __func__);
    g_last_error = "unknown non-standard exception";
    return nullptr;
}


extern "C"
JNIEXPORT void JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_unload(JNIEnv * /*unused*/, jobject /*unused*/) {
    // Reset long-term & short-term states
    reset_long_term_states();
    reset_short_term_states();

    // Free up resources
    common_sampler_free(g_sampler);
    g_chat_templates.reset();
    llama_batch_free(g_batch);
    llama_free(g_context);
    llama_model_free(g_model);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_shutdown(JNIEnv *, jobject /*unused*/) {
    llama_backend_free();
}
