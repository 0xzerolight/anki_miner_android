#include <jni.h>

#include <cstdint>
#include <exception>
#include <limits>
#include <stdexcept>
#include <string>
#include <vector>

#include "tokenizer_wire.h"

namespace {

void ThrowJava(JNIEnv* env, const char* class_name, const char* message) {
  jclass exception_class = env->FindClass(class_name);
  if (exception_class != nullptr) {
    env->ThrowNew(exception_class, message);
  }
}

std::vector<std::uint8_t> ReadBytes(JNIEnv* env, jbyteArray value,
                                    const char* label) {
  if (value == nullptr) {
    throw std::invalid_argument(std::string(label) + " must not be null");
  }
  const jsize size = env->GetArrayLength(value);
  std::vector<std::uint8_t> output(static_cast<std::size_t>(size));
  if (size > 0) {
    env->GetByteArrayRegion(
        value, 0, size,
        reinterpret_cast<jbyte*>(output.data()));  // uint8_t is byte-sized.
    if (env->ExceptionCheck()) {
      throw std::runtime_error(std::string("could not read ") + label);
    }
  }
  return output;
}

std::vector<std::string> ReadArgv(JNIEnv* env, jobjectArray argv_utf8) {
  if (argv_utf8 == nullptr) {
    throw std::invalid_argument("MeCab argv must not be null");
  }
  const jsize count = env->GetArrayLength(argv_utf8);
  std::vector<std::string> output;
  output.reserve(static_cast<std::size_t>(count));
  for (jsize index = 0; index < count; ++index) {
    auto* element = static_cast<jbyteArray>(
        env->GetObjectArrayElement(argv_utf8, index));
    std::vector<std::uint8_t> bytes = ReadBytes(env, element, "MeCab argument");
    env->DeleteLocalRef(element);
    if (bytes.empty()) {
      output.emplace_back();
    } else {
      output.emplace_back(reinterpret_cast<const char*>(bytes.data()),
                          bytes.size());
    }
  }
  return output;
}

jbyteArray ToJavaBytes(JNIEnv* env, const std::vector<std::uint8_t>& value) {
  if (value.size() >
      static_cast<std::size_t>(std::numeric_limits<jsize>::max())) {
    throw std::length_error("native result exceeds Java array limits");
  }
  const auto size = static_cast<jsize>(value.size());
  jbyteArray output = env->NewByteArray(size);
  if (output == nullptr) {
    throw std::runtime_error("could not allocate Java byte array");
  }
  if (size > 0) {
    env->SetByteArrayRegion(
        output, 0, size,
        reinterpret_cast<const jbyte*>(value.data()));  // uint8_t is byte-sized.
    if (env->ExceptionCheck()) {
      throw std::runtime_error("could not populate Java byte array");
    }
  }
  return output;
}

template <typename Operation>
jbyteArray Run(JNIEnv* env, Operation operation) {
  try {
    return ToJavaBytes(env, operation());
  } catch (const std::invalid_argument& error) {
    ThrowJava(env, "java/lang/IllegalArgumentException", error.what());
  } catch (const std::length_error& error) {
    ThrowJava(env, "java/lang/IllegalArgumentException", error.what());
  } catch (const std::exception& error) {
    if (!env->ExceptionCheck()) {
      ThrowJava(env, "java/lang/IllegalStateException", error.what());
    }
  }
  return nullptr;
}

}  // namespace

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_ankiminer_android_tokenizer_MecabNativeTokenizer_nativeTokenize(
    JNIEnv* env, jobject /* receiver */, jbyteArray input_utf8,
    jobjectArray argv_utf8) {
  return Run(env, [&] {
    return anki_miner::tokenizer::NativeTokenizer::Instance().Tokenize(
        ReadBytes(env, input_utf8, "tokenizer input"),
        ReadArgv(env, argv_utf8));
  });
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_ankiminer_android_tokenizer_MecabNativeTokenizer_nativeDictionaryFilename(
    JNIEnv* env, jobject /* receiver */, jobjectArray argv_utf8) {
  return Run(env, [&] {
    const std::vector<std::string> filenames =
        anki_miner::tokenizer::NativeTokenizer::Instance()
            .LoadedDictionaryFilenames(ReadArgv(env, argv_utf8));
    if (filenames.size() != 1) {
      throw std::runtime_error("native tokenizer loaded an invalid dictionary set");
    }
    return std::vector<std::uint8_t>(filenames[0].begin(), filenames[0].end());
  });
}
