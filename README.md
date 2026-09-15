# Depth Mask - HyperOS Style Subject Separator

An Android app that automatically detects subjects in images and creates upper-body-only masks for HyperOS-style depth wallpaper effects.

## Features

- **Auto Subject Detection**: Uses ML Kit to detect people, animals, and objects
- **Upper Body Crop**: Automatically crops mask to show only head/shoulders
- **Manual Editing**: 
  - Crop slider to adjust cut-off point
  - Brush eraser to remove parts of mask
  - Brush restore to add back parts
- **Export**: Save mask as PNG with transparency

## How It Works

1. **Pick an image** from gallery or take a photo
2. **Auto-segmentation** detects the main subject
3. **Upper body crop** automatically isolates head + shoulders
4. **Edit if needed** using crop slider or brush tools
5. **Save mask** as transparent PNG
6. **Import** into your ROM's depth wallpaper settings

## How the HyperOS Effect Works

The depth effect creates a layered look:
- **Background** → **Clock** → **Subject (upper body only)**

By using a mask that only includes the head/shoulders:
- Hours appear **behind** the head (mask covers them)
- Minutes appear **in front** of lower body (no mask there)

This creates the signature HyperOS depth effect with one subject layer!

## Building

1. Open in Android Studio
2. Sync Gradle
3. Build and run on device (API 24+)

## Requirements

- Android 7.0+ (API 24)
- Google Play Services (for ML Kit)
- Camera permission (for camera capture)
- Storage permission (for gallery pick)

## Usage Tips

- **Best results**: Use images with clear subject separation
- **Portrait photos** work best
- **Adjust crop line**: Move slider to control where mask cuts off
- **Use brush**: Fine-tune edges for perfect results
- **Save multiple versions**: Try different crop positions