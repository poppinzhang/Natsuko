# Synthetic video fixtures

These fixtures are generated test patterns, with no third-party media. They are
packaged only in the instrumentation APK. FFmpeg is needed only to regenerate
them on a development machine; tests use the files already committed here.

Generated with FFmpeg 7.1 from the macOS arm64 `imageio-ffmpeg==0.6.0` wheel.
The regular short videos are 96×64, 10 fps, 1 second, YUV420P. A purple square
makes palette quality testable, while the moving test pattern verifies animation.

```sh
source='testsrc2=size=96x64:rate=10:duration=1,drawbox=x=0:y=0:w=24:h=24:color=0x7757CB:t=fill'
ffmpeg -f lavfi -i "$source" -an -c:v libx264 -preset ultrafast -crf 18 -pix_fmt yuv420p h264.mp4
ffmpeg -f lavfi -i "$source" -an -c:v libx265 -preset ultrafast -crf 18 -x265-params log-level=error:pools=1 -pix_fmt yuv420p hevc.mkv
ffmpeg -f lavfi -i "$source" -an -c:v libaom-av1 -cpu-used 8 -crf 20 -threads 2 -pix_fmt yuv420p av1.mp4
ffmpeg -i h264.mp4 -c copy h264.mov
ffmpeg -display_rotation 90 -i h264.mp4 -c copy rotated.mp4
ffmpeg -i h264.mp4 -f lavfi -i sine=frequency=440:sample_rate=44100:duration=1 -map 0:v:0 -map 1:a:0 -c:v copy -c:a aac -b:a 32k -shortest audio.mp4
ffmpeg -f lavfi -i testsrc2=size=4x512:rate=10:duration=1 -an -c:v libx264 -preset ultrafast -crf 18 -pix_fmt yuv420p narrow.mp4
ffmpeg -f lavfi -i testsrc2=size=320x180:rate=30:duration=10 -an -c:v libx264 -preset ultrafast -crf 35 cancel.mp4
ffmpeg -i h264.mp4 -vf scale=8:8 -frames:v 2 sample.gif
```

`rotated.mp4` has a 90-degree display matrix, so displayed output should be
64×96. `cancel.mp4` supplies enough work to cancel after encoding starts.
`sample.gif` is an 8×8 two-frame storage-export fixture.
`audio.mp4` adds an AAC-LC mono sine wave to the regular H.264 source, verifying
that video conversion ignores an audio stream without needing its decoder.
`narrow.mp4` is a 4×512 H.264 test pattern, verifying that palette analysis of
extremely narrow images cannot reduce a dimension to zero or alter output size.
