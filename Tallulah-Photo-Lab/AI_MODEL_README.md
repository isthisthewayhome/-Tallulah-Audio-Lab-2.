# Tallulah Photo Lab v0.3 AI integration

The AI 2× button now has an offline TensorFlow Lite inference implementation. It does **not** ship with a trained model yet, and deliberately reports that limitation instead of silently performing bicubic upscaling.

To enable inference, supply a properly licensed, compatible model at `app/src/main/assets/upscale_x2.tflite`. The current inference adapter expects one fixed-size NHWC float32 RGB input [1,H,W,3] in 0..1 range and one NHWC float32 RGB output [1,2H,2W,3] in 0..1 range, maximum input area 512×512. The implementation resizes the current photo to the model's input size and produces the model's fixed output size. This is **not yet a general-purpose full-resolution/tiled upscaler**. Models with different preprocessing, output normalization, tensor layout or dynamic shapes require adapter changes.

All processing occurs on the device. The v0.2 code is preserved in Git history.
