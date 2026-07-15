#include <cstdint>
#include <fstream>
#include <iostream>
#include <stdexcept>
#include <string>
#include <thread>
#include <vector>

#include "tokenizer_wire.h"

namespace {

std::uint16_t ReadU16(const std::vector<std::uint8_t>& value,
                      std::size_t offset) {
  return static_cast<std::uint16_t>(value.at(offset)) |
         static_cast<std::uint16_t>(value.at(offset + 1)) << 8U;
}

std::uint32_t ReadU32(const std::vector<std::uint8_t>& value,
                      std::size_t offset) {
  std::uint32_t output = 0;
  for (unsigned shift = 0; shift < 32; shift += 8) {
    output |= static_cast<std::uint32_t>(value.at(offset + shift / 8)) << shift;
  }
  return output;
}

void Require(bool condition, const char* message) {
  if (!condition) {
    throw std::runtime_error(message);
  }
}

bool MapsContain(const std::string& expected) {
  std::ifstream maps("/proc/self/maps");
  for (std::string line; std::getline(maps, line);) {
    if (line.find(expected) != std::string::npos) {
      return true;
    }
  }
  return false;
}

}  // namespace

int main(int argc, char** argv) {
  try {
    if (argc != 2) {
      std::cerr << "usage: s1b_native_host_test <unidic-dicdir>\n";
      return 2;
    }
    const std::string dicdir = argv[1];
    const std::vector<std::string> mecab_argv = {
        "anki_miner", "-C", "-r", dicdir + "/mecabrc", "-d", dicdir};
    auto& tokenizer = anki_miner::tokenizer::NativeTokenizer::Instance();

    const std::vector<std::string> filenames =
        tokenizer.LoadedDictionaryFilenames(mecab_argv);
    Require(filenames == std::vector<std::string>{dicdir + "/sys.dic"},
            "wrong loaded dictionary set");
    Require(MapsContain(dicdir + "/sys.dic"), "sys.dic is not memory-mapped");
    Require(MapsContain(dicdir + "/matrix.bin"),
            "matrix.bin is not memory-mapped");

    const std::string text = "猫𠮟𠮟𠮟犬";
    const std::vector<std::uint8_t> input(text.begin(), text.end());
    const std::vector<std::uint8_t> wire = tokenizer.Tokenize(input, mecab_argv);
    Require(wire.size() >= 16, "wire is too short");
    Require(std::string(wire.begin(), wire.begin() + 4) == "AMTK",
            "wire magic mismatch");
    Require(ReadU16(wire, 4) == 1, "wire version mismatch");
    Require(ReadU16(wire, 6) == 0, "wire flags mismatch");
    Require(ReadU32(wire, 8) == input.size(), "wire input length mismatch");
    Require(ReadU32(wire, 12) > 0, "wire has no tokens");

    const std::string spaced_text = " 猫  犬 ";
    const std::vector<std::uint8_t> spaced_input(spaced_text.begin(),
                                                  spaced_text.end());
    const std::vector<std::uint8_t> spaced_wire =
        tokenizer.Tokenize(spaced_input, mecab_argv);
    Require(ReadU32(spaced_wire, 12) == 2, "whitespace parse token count mismatch");
    Require(ReadU32(spaced_wire, 16) == 1, "first token byte start mismatch");
    Require(ReadU32(spaced_wire, 20) == 4, "first token byte end mismatch");
    Require(ReadU32(spaced_wire, 24) == 4, "first token rlength mismatch");

    std::vector<std::uint8_t> second_wire;
    std::thread concurrent([&] {
      second_wire = tokenizer.Tokenize(input, mecab_argv);
    });
    concurrent.join();
    Require(second_wire == wire, "serialized concurrent parse is unstable");

    std::vector<std::string> changed = mecab_argv;
    changed[5] += "-other";
    bool rejected = false;
    try {
      static_cast<void>(tokenizer.LoadedDictionaryFilenames(changed));
    } catch (const std::invalid_argument&) {
      rejected = true;
    }
    Require(rejected, "process-immutable argv change was accepted");

    std::cout << "s1b native host test: OK\n";
  } catch (const std::exception& error) {
    std::cerr << "s1b native host test: " << error.what() << '\n';
    return 1;
  }
  return 0;
}
