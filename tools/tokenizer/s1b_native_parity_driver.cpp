#include <array>
#include <cstdint>
#include <iostream>
#include <stdexcept>
#include <string>
#include <vector>

#include "tokenizer_wire.h"

namespace {

bool ReadU32(std::uint32_t* output) {
  std::array<unsigned char, 4> bytes{};
  std::cin.read(reinterpret_cast<char*>(bytes.data()), bytes.size());
  if (std::cin.gcount() == 0 && std::cin.eof()) {
    return false;
  }
  if (std::cin.gcount() != static_cast<std::streamsize>(bytes.size())) {
    throw std::runtime_error("truncated input frame header");
  }
  *output = static_cast<std::uint32_t>(bytes[0]) |
            static_cast<std::uint32_t>(bytes[1]) << 8U |
            static_cast<std::uint32_t>(bytes[2]) << 16U |
            static_cast<std::uint32_t>(bytes[3]) << 24U;
  return true;
}

void WriteU32(std::uint32_t value) {
  const std::array<unsigned char, 4> bytes = {
      static_cast<unsigned char>(value & 0xFFU),
      static_cast<unsigned char>((value >> 8U) & 0xFFU),
      static_cast<unsigned char>((value >> 16U) & 0xFFU),
      static_cast<unsigned char>((value >> 24U) & 0xFFU),
  };
  std::cout.write(reinterpret_cast<const char*>(bytes.data()), bytes.size());
}

}  // namespace

int main(int argc, char** argv) {
  try {
    if (argc != 2) {
      std::cerr << "usage: s1b_native_parity_driver <unidic-dicdir>\n";
      return 2;
    }
    const std::string dicdir = argv[1];
    const std::vector<std::string> mecab_argv = {
        "anki_miner", "-C", "-r", dicdir + "/mecabrc", "-d", dicdir};
    auto& tokenizer = anki_miner::tokenizer::NativeTokenizer::Instance();
    static_cast<void>(tokenizer.LoadedDictionaryFilenames(mecab_argv));

    std::uint32_t input_size = 0;
    while (ReadU32(&input_size)) {
      std::vector<std::uint8_t> input(input_size);
      std::cin.read(reinterpret_cast<char*>(input.data()), input.size());
      if (std::cin.gcount() != static_cast<std::streamsize>(input.size())) {
        throw std::runtime_error("truncated input frame");
      }
      const std::vector<std::uint8_t> wire =
          tokenizer.Tokenize(input, mecab_argv);
      WriteU32(static_cast<std::uint32_t>(wire.size()));
      std::cout.write(reinterpret_cast<const char*>(wire.data()), wire.size());
      std::cout.flush();
    }
  } catch (const std::exception& error) {
    std::cerr << "s1b native parity driver: " << error.what() << '\n';
    return 1;
  }
  return 0;
}
