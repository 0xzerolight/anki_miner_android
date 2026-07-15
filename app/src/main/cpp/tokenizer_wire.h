#ifndef ANKI_MINER_TOKENIZER_WIRE_H_
#define ANKI_MINER_TOKENIZER_WIRE_H_

#include <cstdint>
#include <mutex>
#include <string>
#include <vector>

#include "mecab.h"

namespace anki_miner::tokenizer {

class NativeTokenizer final {
 public:
  static NativeTokenizer& Instance();

  NativeTokenizer(const NativeTokenizer&) = delete;
  NativeTokenizer& operator=(const NativeTokenizer&) = delete;

  std::vector<std::uint8_t> Tokenize(
      const std::vector<std::uint8_t>& input_utf8,
      const std::vector<std::string>& mecab_new_argv);
  std::vector<std::string> LoadedDictionaryFilenames(
      const std::vector<std::string>& mecab_new_argv);

 private:
  NativeTokenizer() = default;
  ~NativeTokenizer();

  void EnsureTaggerLocked(const std::vector<std::string>& mecab_new_argv);

  std::mutex mutex_;
  mecab_t* tagger_ = nullptr;
  std::vector<std::string> argv_;
  std::vector<std::string> dictionary_filenames_;
};

}  // namespace anki_miner::tokenizer

#endif  // ANKI_MINER_TOKENIZER_WIRE_H_
