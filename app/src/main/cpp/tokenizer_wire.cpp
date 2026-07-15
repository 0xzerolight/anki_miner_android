#include "tokenizer_wire.h"

#include <algorithm>
#include <array>
#include <climits>
#include <cstddef>
#include <cstring>
#include <limits>
#include <stdexcept>
#include <string_view>
#include <type_traits>
#include <utility>

namespace anki_miner::tokenizer {
namespace {

constexpr std::array<std::uint8_t, 4> kMagic = {'A', 'M', 'T', 'K'};
constexpr std::uint16_t kWireVersion = 1;
constexpr std::size_t kHeaderSize = 16;
constexpr std::size_t kRecordSize = 28;
constexpr std::size_t kMaxFeatureBytes = 1U << 20;

using NodePosId = decltype(mecab_node_t::posid);
using NodeLeftAttribute = decltype(mecab_node_t::lcAttr);
using NodeLength = decltype(mecab_node_t::length);
using NodeRawLength = decltype(mecab_node_t::rlength);
using NodeCharacterType = decltype(mecab_node_t::char_type);
using NodeStatus = decltype(mecab_node_t::stat);
static_assert(std::is_unsigned_v<NodePosId> && sizeof(NodePosId) <= 2);
static_assert(
    std::is_unsigned_v<NodeLeftAttribute> && sizeof(NodeLeftAttribute) <= 2);
static_assert(std::is_unsigned_v<NodeLength> && sizeof(NodeLength) <= 2);
static_assert(std::is_unsigned_v<NodeRawLength> && sizeof(NodeRawLength) <= 2);
static_assert(
    std::is_unsigned_v<NodeCharacterType> && sizeof(NodeCharacterType) == 1);
static_assert(std::is_unsigned_v<NodeStatus> && sizeof(NodeStatus) == 1);

bool IsContinuation(std::uint8_t value) { return (value & 0xC0U) == 0x80U; }

bool IsStrictUtf8(const std::vector<std::uint8_t>& input) {
  std::size_t index = 0;
  while (index < input.size()) {
    const std::uint8_t first = input[index];
    if (first <= 0x7FU) {
      ++index;
      continue;
    }
    if (first >= 0xC2U && first <= 0xDFU) {
      if (index + 1 >= input.size() || !IsContinuation(input[index + 1])) {
        return false;
      }
      index += 2;
      continue;
    }
    if (first >= 0xE0U && first <= 0xEFU) {
      if (index + 2 >= input.size() || !IsContinuation(input[index + 1]) ||
          !IsContinuation(input[index + 2])) {
        return false;
      }
      const std::uint8_t second = input[index + 1];
      if ((first == 0xE0U && second < 0xA0U) ||
          (first == 0xEDU && second >= 0xA0U)) {
        return false;
      }
      index += 3;
      continue;
    }
    if (first >= 0xF0U && first <= 0xF4U) {
      if (index + 3 >= input.size() || !IsContinuation(input[index + 1]) ||
          !IsContinuation(input[index + 2]) ||
          !IsContinuation(input[index + 3])) {
        return false;
      }
      const std::uint8_t second = input[index + 1];
      if ((first == 0xF0U && second < 0x90U) ||
          (first == 0xF4U && second >= 0x90U)) {
        return false;
      }
      index += 4;
      continue;
    }
    return false;
  }
  return true;
}

void AppendU16(std::vector<std::uint8_t>* output, std::uint16_t value) {
  output->push_back(static_cast<std::uint8_t>(value & 0xFFU));
  output->push_back(static_cast<std::uint8_t>((value >> 8U) & 0xFFU));
}

void AppendU32(std::vector<std::uint8_t>* output, std::uint32_t value) {
  for (unsigned shift = 0; shift < 32; shift += 8) {
    output->push_back(static_cast<std::uint8_t>((value >> shift) & 0xFFU));
  }
}

void StoreU32(std::vector<std::uint8_t>* output, std::size_t offset,
              std::uint32_t value) {
  for (unsigned shift = 0; shift < 32; shift += 8) {
    output->at(offset + shift / 8) =
        static_cast<std::uint8_t>((value >> shift) & 0xFFU);
  }
}

void EnsureJavaArraySize(std::size_t size) {
  if (size >
      static_cast<std::size_t>(std::numeric_limits<std::int32_t>::max())) {
    throw std::length_error("native tokenizer result exceeds Java array limits");
  }
}

void ValidateArgv(const std::vector<std::string>& argv) {
  if (argv.size() != 6 || argv[0] != "anki_miner" || argv[1] != "-C" ||
      argv[2] != "-r" || argv[4] != "-d") {
    throw std::invalid_argument(
        "mecab_new argv must be: anki_miner -C -r <mecabrc> -d <dicdir>");
  }
  if (argv[3].empty() || argv[5].empty() || argv[3].front() != '/' ||
      argv[5].front() != '/') {
    throw std::invalid_argument("MeCab resource paths must be absolute");
  }
  if (argv[3] != argv[5] + "/mecabrc") {
    throw std::invalid_argument("MeCab rcfile must belong to the dictionary");
  }
  for (const std::string& argument : argv) {
    if (argument.empty() || argument.find('\0') != std::string::npos) {
      throw std::invalid_argument("MeCab argv contains an empty or NUL value");
    }
  }
}

std::vector<char*> MutableArgv(std::vector<std::string>* values) {
  std::vector<char*> pointers;
  pointers.reserve(values->size());
  for (std::string& value : *values) {
    pointers.push_back(value.data());
  }
  return pointers;
}

std::string ExpectedSystemDictionary(const std::vector<std::string>& argv) {
  return argv[5] + "/sys.dic";
}

void AppendRecord(std::vector<std::uint8_t>* output, std::uint32_t byte_start,
                  std::uint32_t byte_end, const mecab_node_t& node,
                  std::string_view feature) {
  const std::size_t required = kRecordSize + feature.size();
  EnsureJavaArraySize(output->size() + required);
  AppendU32(output, byte_start);
  AppendU32(output, byte_end);
  AppendU32(output, node.rlength);
  AppendU32(output, node.posid);
  AppendU32(output, node.char_type);
  output->push_back(node.stat);
  output->insert(output->end(), 3, 0);
  AppendU32(output, static_cast<std::uint32_t>(feature.size()));
  output->insert(output->end(), feature.begin(), feature.end());
}

}  // namespace

NativeTokenizer& NativeTokenizer::Instance() {
  static NativeTokenizer tokenizer;
  return tokenizer;
}

NativeTokenizer::~NativeTokenizer() {
  if (tagger_ != nullptr) {
    mecab_destroy(tagger_);
  }
}

void NativeTokenizer::EnsureTaggerLocked(
    const std::vector<std::string>& mecab_new_argv) {
  ValidateArgv(mecab_new_argv);
  if (tagger_ != nullptr) {
    if (argv_ != mecab_new_argv) {
      throw std::invalid_argument(
          "native tokenizer dictionary is immutable for the process");
    }
    return;
  }

  std::vector<std::string> mutable_values = mecab_new_argv;
  std::vector<char*> argv_pointers = MutableArgv(&mutable_values);
  mecab_t* candidate =
      mecab_new(static_cast<int>(argv_pointers.size()), argv_pointers.data());
  if (candidate == nullptr) {
    throw std::runtime_error(std::string("mecab_new failed: ") +
                             mecab_strerror(nullptr));
  }

  const mecab_dictionary_info_t* info = mecab_dictionary_info(candidate);
  const std::string expected = ExpectedSystemDictionary(mecab_new_argv);
  if (info == nullptr || info->filename == nullptr || info->next != nullptr ||
      expected != info->filename) {
    mecab_destroy(candidate);
    throw std::runtime_error(
        "MeCab did not load exactly the registered system dictionary");
  }

  tagger_ = candidate;
  argv_ = mecab_new_argv;
  dictionary_filenames_ = {info->filename};
}

std::vector<std::string> NativeTokenizer::LoadedDictionaryFilenames(
    const std::vector<std::string>& mecab_new_argv) {
  std::lock_guard<std::mutex> lock(mutex_);
  EnsureTaggerLocked(mecab_new_argv);
  return dictionary_filenames_;
}

std::vector<std::uint8_t> NativeTokenizer::Tokenize(
    const std::vector<std::uint8_t>& input_utf8,
    const std::vector<std::string>& mecab_new_argv) {
  if (input_utf8.size() > std::numeric_limits<std::uint32_t>::max()) {
    throw std::length_error("tokenizer input exceeds AMTK v1 limits");
  }
  if (!IsStrictUtf8(input_utf8) ||
      std::find(input_utf8.begin(), input_utf8.end(), 0) != input_utf8.end()) {
    throw std::invalid_argument("tokenizer input must be strict, NUL-free UTF-8");
  }

  std::lock_guard<std::mutex> lock(mutex_);
  EnsureTaggerLocked(mecab_new_argv);
  const char* input = input_utf8.empty()
                          ? ""
                          : reinterpret_cast<const char*>(input_utf8.data());
  const mecab_node_t* node =
      mecab_sparse_tonode2(tagger_, input, input_utf8.size());
  if (node == nullptr) {
    throw std::runtime_error(std::string("MeCab parse failed: ") +
                             mecab_strerror(tagger_));
  }

  std::vector<std::uint8_t> output;
  output.reserve(kHeaderSize + input_utf8.size());
  output.insert(output.end(), kMagic.begin(), kMagic.end());
  AppendU16(&output, kWireVersion);
  AppendU16(&output, 0);
  AppendU32(&output, static_cast<std::uint32_t>(input_utf8.size()));
  AppendU32(&output, 0);

  std::uint32_t cursor = 0;
  std::uint32_t token_count = 0;
  for (; node != nullptr; node = node->next) {
    if (node->stat == MECAB_BOS_NODE || node->stat == MECAB_EOS_NODE ||
        node->stat == MECAB_EON_NODE) {
      continue;
    }
    if (node->stat != MECAB_NOR_NODE && node->stat != MECAB_UNK_NODE) {
      throw std::runtime_error("MeCab returned an unsupported node status");
    }
    if (node->surface == nullptr || node->feature == nullptr ||
        node->length == 0 || node->rlength < node->length) {
      throw std::runtime_error("MeCab returned an invalid node");
    }

    const std::uint64_t byte_end =
        static_cast<std::uint64_t>(cursor) + node->rlength;
    if (byte_end > input_utf8.size()) {
      throw std::runtime_error("MeCab node extends beyond tokenizer input");
    }
    const std::uint32_t byte_end32 = static_cast<std::uint32_t>(byte_end);
    const std::uint32_t byte_start = byte_end32 - node->length;
    if (std::memcmp(input_utf8.data() + byte_start, node->surface,
                    node->length) != 0) {
      throw std::runtime_error("MeCab node surface disagrees with tokenizer input");
    }

    const std::size_t feature_size =
        ::strnlen(node->feature, kMaxFeatureBytes + 1);
    if (feature_size > kMaxFeatureBytes) {
      throw std::runtime_error("MeCab feature row exceeds AMTK v1 limits");
    }
    const std::string_view feature(node->feature, feature_size);
    if (feature.find('\r') != std::string_view::npos ||
        feature.find('\n') != std::string_view::npos) {
      throw std::runtime_error("MeCab feature row is not a single CSV record");
    }

    AppendRecord(&output, byte_start, byte_end32, *node, feature);
    cursor = byte_end32;
    if (token_count == std::numeric_limits<std::uint32_t>::max()) {
      throw std::length_error("token count exceeds AMTK v1 limits");
    }
    ++token_count;
  }

  StoreU32(&output, 12, token_count);
  EnsureJavaArraySize(output.size());
  return output;
}

}  // namespace anki_miner::tokenizer
