#include <jni.h>

#include <algorithm>
#include <cstdio>
#include <fstream>
#include <memory>
#include <mutex>
#include <sstream>
#include <stdexcept>
#include <string>
#include <vector>

#include <libime/core/userlanguagemodel.h>
#include <libime/pinyin/pinyincorrectionprofile.h>
#include <libime/pinyin/pinyindictionary.h>
#include <libime/pinyin/pinyincontext.h>
#include <libime/pinyin/pinyinime.h>

namespace {

std::mutex gMutex;
std::unique_ptr<libime::PinyinIME> gIme;
std::unique_ptr<libime::PinyinContext> gContext;
std::string gHistoryPath;

std::string toString(JNIEnv *env, jstring value) {
    if (value == nullptr) {
        return {};
    }
    const auto *chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) {
        return {};
    }
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

jstring toJString(JNIEnv *env, const std::string &value) {
    return env->NewStringUTF(value.c_str());
}

void throwIllegalState(JNIEnv *env, const char *message) {
    env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), message);
}

void saveHistoryLocked() {
    if (!gIme || gHistoryPath.empty()) {
        return;
    }
    const auto tempPath = gHistoryPath + ".tmp";
    std::ofstream out(tempPath, std::ios::binary | std::ios::trunc);
    if (!out.is_open()) {
        return;
    }
    gIme->model()->save(out);
    out.close();
    if (!out.fail()) {
        std::remove(gHistoryPath.c_str());
        std::rename(tempPath.c_str(), gHistoryPath.c_str());
    }
}

void setFuzzyOptionsLocked() {
    libime::PinyinFuzzyFlags flags;
    flags |= libime::PinyinFuzzyFlag::CommonTypo;
    flags |= libime::PinyinFuzzyFlag::AdvancedTypo;
    flags |= libime::PinyinFuzzyFlag::Correction;
    flags |= libime::PinyinFuzzyFlag::V_U;
    flags |= libime::PinyinFuzzyFlag::VE_UE;
    flags |= libime::PinyinFuzzyFlag::AN_ANG;
    flags |= libime::PinyinFuzzyFlag::EN_ENG;
    flags |= libime::PinyinFuzzyFlag::IAN_IANG;
    flags |= libime::PinyinFuzzyFlag::IN_ING;
    flags |= libime::PinyinFuzzyFlag::U_OU;
    flags |= libime::PinyinFuzzyFlag::UAN_UANG;
    flags |= libime::PinyinFuzzyFlag::C_CH;
    flags |= libime::PinyinFuzzyFlag::F_H;
    flags |= libime::PinyinFuzzyFlag::L_N;
    flags |= libime::PinyinFuzzyFlag::S_SH;
    flags |= libime::PinyinFuzzyFlag::Z_ZH;
    gIme->setFuzzyFlags(flags);
    gIme->setCorrectionProfile(std::make_shared<libime::PinyinCorrectionProfile>(
        libime::BuiltinPinyinCorrectionProfile::Qwerty));
    gIme->setNBest(1);
    gIme->setWordCandidateLimit(24);
    gIme->setScoreFilter(1.0F);
    gIme->setPreeditMode(libime::PinyinPreeditMode::RawText);
}

bool loadBinaryDictionary(libime::PinyinDictionary &dict, std::size_t index,
                          const std::string &path) {
    std::ifstream in(path, std::ios::binary);
    if (!in.is_open()) {
        return false;
    }
    dict.load(index, in, libime::PinyinDictFormat::Binary);
    return true;
}

}  // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_com_agentkeyboard_ime_pinyin_LibimePinyinEngine_nativeInit(
    JNIEnv *env, jclass, jstring dictionaryPath, jstring modelPath,
    jstring historyPath, jstring extraDictionaryPath) {
    std::lock_guard lock(gMutex);
    try {
        const auto dictPath = toString(env, dictionaryPath);
        const auto lmPath = toString(env, modelPath);
        gHistoryPath = toString(env, historyPath);
        const auto extraPath = toString(env, extraDictionaryPath);
        if (dictPath.empty() || lmPath.empty()) {
            throw std::runtime_error("libime data path is empty");
        }

        auto dictionary = std::make_unique<libime::PinyinDictionary>();
        if (!loadBinaryDictionary(*dictionary, libime::PinyinDictionary::SystemDict,
                                  dictPath)) {
            throw std::runtime_error("failed to open libime pinyin dictionary");
        }
        if (!extraPath.empty()) {
            dictionary->addEmptyDict();
            loadBinaryDictionary(*dictionary, libime::PinyinDictionary::UserDict + 1,
                                 extraPath);
        }

        auto model = std::make_unique<libime::UserLanguageModel>(lmPath.c_str());
        if (!gHistoryPath.empty()) {
            std::ifstream history(gHistoryPath, std::ios::binary);
            if (history.is_open()) {
                model->load(history);
            }
        }

        gIme = std::make_unique<libime::PinyinIME>(
            std::move(dictionary), std::move(model));
        setFuzzyOptionsLocked();
        gContext = std::make_unique<libime::PinyinContext>(gIme.get());
        gContext->setMaxSentenceLength(64);
        return JNI_TRUE;
    } catch (const std::exception &exception) {
        gContext.reset();
        gIme.reset();
        gHistoryPath.clear();
        throwIllegalState(env, exception.what());
        return JNI_FALSE;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_agentkeyboard_ime_pinyin_LibimePinyinEngine_nativeDestroy(
    JNIEnv *, jclass) {
    std::lock_guard lock(gMutex);
    saveHistoryLocked();
    gContext.reset();
    gIme.reset();
    gHistoryPath.clear();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_agentkeyboard_ime_pinyin_LibimePinyinEngine_nativeSetInput(
    JNIEnv *env, jclass, jstring input) {
    std::lock_guard lock(gMutex);
    if (!gContext) {
        throwIllegalState(env, "libime engine is not initialized");
        return JNI_FALSE;
    }
    const auto value = toString(env, input);
    gContext->clear();
    if (value.empty()) {
        return JNI_TRUE;
    }
    return gContext->type(value.data(), value.size()) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_agentkeyboard_ime_pinyin_LibimePinyinEngine_nativeCandidates(
    JNIEnv *env, jclass, jint limit) {
    std::lock_guard lock(gMutex);
    if (!gContext) {
        throwIllegalState(env, "libime engine is not initialized");
        return nullptr;
    }
    const auto &candidates = gContext->candidates();
    const auto count = static_cast<jsize>(
        std::min<std::size_t>(candidates.size(), std::max(limit, 0)));
    const auto stringClass = env->FindClass("java/lang/String");
    auto result = env->NewObjectArray(count, stringClass, nullptr);
    for (jsize index = 0; index < count; ++index) {
        env->SetObjectArrayElement(
            result, index, toJString(env, candidates[index].toString()));
    }
    return result;
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_agentkeyboard_ime_pinyin_LibimePinyinEngine_nativeCandidateScores(
    JNIEnv *env, jclass, jint limit) {
    std::lock_guard lock(gMutex);
    if (!gContext) {
        throwIllegalState(env, "libime engine is not initialized");
        return nullptr;
    }
    const auto &candidates = gContext->candidates();
    const auto count = static_cast<jsize>(
        std::min<std::size_t>(candidates.size(), std::max(limit, 0)));
    auto result = env->NewFloatArray(count);
    if (result == nullptr) {
        return nullptr;
    }
    std::vector<jfloat> scores;
    scores.reserve(count);
    for (jsize index = 0; index < count; ++index) {
        scores.push_back(candidates[index].score());
    }
    env->SetFloatArrayRegion(result, 0, count, scores.data());
    return result;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_agentkeyboard_ime_pinyin_LibimePinyinEngine_nativeSelectCandidate(
    JNIEnv *env, jclass, jint index) {
    std::lock_guard lock(gMutex);
    if (!gContext) {
        throwIllegalState(env, "libime engine is not initialized");
        return nullptr;
    }
    const auto &candidates = gContext->candidates();
    if (index < 0 || static_cast<std::size_t>(index) >= candidates.size()) {
        return nullptr;
    }
    gContext->select(static_cast<std::size_t>(index));
    const auto result = gContext->selectedSentence();
    gContext->learn();
    saveHistoryLocked();
    return toJString(env, result);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_agentkeyboard_ime_pinyin_LibimePinyinEngine_nativeTopCandidate(
    JNIEnv *env, jclass) {
    std::lock_guard lock(gMutex);
    if (!gContext) {
        throwIllegalState(env, "libime engine is not initialized");
        return nullptr;
    }
    const auto &candidates = gContext->candidates();
    if (candidates.empty()) {
        return nullptr;
    }
    return toJString(env, candidates.front().toString());
}

extern "C" JNIEXPORT void JNICALL
Java_com_agentkeyboard_ime_pinyin_LibimePinyinEngine_nativeReset(
    JNIEnv *env, jclass) {
    std::lock_guard lock(gMutex);
    if (!gContext) {
        throwIllegalState(env, "libime engine is not initialized");
        return;
    }
    gContext->clear();
}

extern "C" JNIEXPORT void JNICALL
Java_com_agentkeyboard_ime_pinyin_LibimePinyinEngine_nativeSaveHistory(
    JNIEnv *, jclass) {
    std::lock_guard lock(gMutex);
    saveHistoryLocked();
}
