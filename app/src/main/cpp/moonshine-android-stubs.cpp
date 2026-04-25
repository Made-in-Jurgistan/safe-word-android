/*
 * moonshine-android-stubs.cpp
 *
 * Minimal linker stubs for MoonshineModel (non-streaming model) and word-alignment.
 *
 * On Android we always use model_arch=SMALL_STREAMING, so MoonshineModel is
 * never instantiated.  However, transcriber.cpp still compiles the non-streaming
 * branches (else-clauses guarded by is_streaming_model_arch()), which reference
 * MoonshineModel symbols.  These stubs satisfy the linker without pulling in the
 * ~45 KB of dead ORT session code from moonshine-model.cpp, word-alignment.cpp,
 * and cosine-distance.cpp.
 *
 * Return value convention: non-zero = error (same as the real implementations).
 */

#include "moonshine-model.h"
#include "word-alignment.h"

MoonshineModel::MoonshineModel(bool /*log_ort_run*/,
                               float /*max_tokens_per_second*/) {}

MoonshineModel::~MoonshineModel() {}

int MoonshineModel::load(const char * /*encoder_model_path*/,
                         const char * /*decoder_model_path*/,
                         const char * /*tokenizer_path*/,
                         int32_t /*model_type*/) {
  return 1;
}

int MoonshineModel::load_alignment_model(const char * /*alignment_model_path*/) {
  return 1;
}

int MoonshineModel::load_from_memory(
    const uint8_t * /*encoder_model_data*/,
    size_t /*encoder_model_data_size*/,
    const uint8_t * /*decoder_model_data*/,
    size_t /*decoder_model_data_size*/,
    const uint8_t * /*tokenizer_data*/,
    size_t /*tokenizer_data_size*/,
    int32_t /*model_type*/) {
  return 1;
}

int MoonshineModel::transcribe(const float * /*input_audio_data*/,
                               size_t /*input_audio_data_size*/,
                               char ** /*out_text*/) {
  return 1;
}

int MoonshineModel::compute_word_timestamps(
    float /*audio_duration*/,
    std::vector<TranscriberWord> & /*words_out*/) {
  return 1;
}

// Word alignment stubs — transcriber.cpp references align_words() for streaming
// model cross-attention word timestamps, but we never set word_timestamps=true
// on Android (disabled in MoonshineNativeBridge options).
//
// Returns empty vector so the downstream loop simply produces no word entries.
std::vector<TranscriberWord> align_words(
    const float* /*cross_attention_data*/,
    int /*num_layers*/, int /*num_heads*/, int /*num_tokens*/, int /*encoder_frames*/,
    const std::vector<int>& /*tokens*/,
    float /*time_per_frame*/,
    BinTokenizer* /*tokenizer*/) {
  return {};
}
