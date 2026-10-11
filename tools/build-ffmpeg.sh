#!/bin/bash
# Builds jniLibs/<abi>/libffmpeg.so (copy out2/<abi>/bin/ffmpeg there). Needs NDK r28c at $NDK,
# nasm and pkg-config; run in Linux/WSL. ffmpeg-src is a checkout of FFmpeg tag n7.1.
# Comfort ffmpeg v2: audio encoders (aac, mp3, opus, flac), software decoders, image codecs,
# MediaCodec (NDK) H.264/HEVC encode, and the filters the app uses. Same build for all 3 ABIs.
set -euo pipefail

NDK=/tmp/ndk-linux/android-ndk-r28c
TOOLCHAIN=$NDK/toolchains/llvm/prebuilt/linux-x86_64
API=24
ROOT=$HOME/ffmpeg-build
SRC=$ROOT/ffmpeg-src
DL=$ROOT/deps-src
OUT_ROOT=$ROOT/out2
LAME_VER=3.100
OPUS_VER=1.5.2

mkdir -p "$DL"
cd "$DL"
[ -d lame-$LAME_VER ] || { curl -fsSL --retry 8 --retry-all-errors --retry-delay 2 -o lame.tar.gz "https://downloads.sourceforge.net/project/lame/lame/$LAME_VER/lame-$LAME_VER.tar.gz"; tar xf lame.tar.gz; }
[ -d opus-$OPUS_VER ] || { curl -fsSL --retry 8 --retry-all-errors --retry-delay 2 -o opus.tar.gz "https://downloads.xiph.org/releases/opus/opus-$OPUS_VER.tar.gz"; tar xf opus.tar.gz; }

build_one() {
  local ABI=$1 ARCH=$2 TRIPLE=$3 CPU=$4 HOST=$5
  local CC=$TOOLCHAIN/bin/${TRIPLE}${API}-clang
  local PREFIX=$OUT_ROOT/$ABI
  local DEPS=$OUT_ROOT/deps-$ABI
  export AR=$TOOLCHAIN/bin/llvm-ar RANLIB=$TOOLCHAIN/bin/llvm-ranlib STRIP=$TOOLCHAIN/bin/llvm-strip
  export CC CXX=${CC}++ CFLAGS="-O2 -fPIC"

  rm -rf "$DEPS" && mkdir -p "$DEPS"

  # LAME (static, encoder library only)
  rm -rf "$ROOT/b-lame-$ABI" && cp -a "$DL/lame-$LAME_VER" "$ROOT/b-lame-$ABI" && cd "$ROOT/b-lame-$ABI"
  ./configure --host=$HOST --prefix="$DEPS" --enable-static --disable-shared --disable-frontend \
    --disable-decoder --disable-gtktest --disable-analyzer-hooks >/dev/null
  make -j"$(nproc)" >/dev/null && make install >/dev/null

  # Opus (static)
  rm -rf "$ROOT/b-opus-$ABI" && cp -a "$DL/opus-$OPUS_VER" "$ROOT/b-opus-$ABI" && cd "$ROOT/b-opus-$ABI"
  ./configure --host=$HOST --prefix="$DEPS" --enable-static --disable-shared --disable-doc \
    --disable-extra-programs >/dev/null
  make -j"$(nproc)" >/dev/null && make install >/dev/null

  unset CFLAGS
  local BUILD=$ROOT/build2-$ABI
  rm -rf "$BUILD" && mkdir -p "$BUILD" && cd "$BUILD"

  local EXTRA=""
  [ "$ABI" = "armeabi-v7a" ] && EXTRA="--enable-neon"

  PKG_CONFIG_PATH="$DEPS/lib/pkgconfig" PKG_CONFIG_LIBDIR="$DEPS/lib/pkgconfig" \
  "$SRC/configure" \
    --prefix="$PREFIX" \
    --target-os=android --arch=$ARCH --cpu=$CPU $EXTRA \
    --cc="$CC" --cxx="${CC}++" \
    --ar=$AR --ranlib=$RANLIB --strip=$STRIP --nm=$TOOLCHAIN/bin/llvm-nm \
    --enable-cross-compile --sysroot=$TOOLCHAIN/sysroot \
    --extra-cflags="-O2 -fPIC -I$DEPS/include" \
    --extra-ldflags="-static-libgcc -L$DEPS/lib" \
    --pkg-config="$(which pkg-config)" --pkg-config-flags="--static" \
    --disable-shared --enable-static \
    --disable-programs --enable-ffmpeg \
    --disable-avdevice --disable-postproc --disable-network \
    --disable-debug --disable-doc --disable-symver \
    --disable-everything \
    --enable-zlib --enable-libmp3lame --enable-libopus \
    --enable-jni --enable-mediacodec \
    --enable-protocol=file,pipe \
    --enable-demuxer=mov,matroska,wav,mp3,aac,ogg,flac,flv,mpegts,concat,image2,image2pipe,png_pipe,jpeg_pipe,webp_pipe,gif \
    --enable-muxer=mp4,ipod,mov,matroska,webm,adts,wav,mp3,ogg,opus,flac,image2,gif,null \
    --enable-parser=h264,hevc,aac,mpegaudio,vp8,vp9,av1,opus,vorbis,flac,mjpeg,png,webp,gif \
    --enable-bsf=h264_mp4toannexb,hevc_mp4toannexb,aac_adtstoasc,extract_extradata,vp9_superframe,vp9_superframe_split \
    --enable-decoder=aac,aac_latm,mp3float,mp3,opus,vorbis,flac,alac,pcm_s16le,pcm_s24le,pcm_f32le,h264,hevc,vp8,vp9,mpeg4,mjpeg,png,webp,gif,bmp,h264_mediacodec,hevc_mediacodec,vp8_mediacodec,vp9_mediacodec,av1_mediacodec \
    --enable-encoder=aac,libmp3lame,libopus,flac,alac,pcm_s16le,mjpeg,png,gif,h264_mediacodec,hevc_mediacodec \
    --enable-filter=aresample,anull,null,scale,format,aformat,volume,loudnorm,atrim,trim,setpts,asetpts,fps,palettegen,paletteuse,split,crop,pad,concat,thumbnail,select,transpose,afade,fade \
    --disable-hwaccels --disable-indevs --disable-outdevs \
    > "$BUILD/configure.log" 2>&1 || { tail -40 "$BUILD/configure.log"; exit 1; }

  make -j"$(nproc)" > "$BUILD/make.log" 2>&1 || { tail -60 "$BUILD/make.log"; exit 1; }
  make install >/dev/null
  $STRIP "$PREFIX/bin/ffmpeg"
  echo "$ABI: $(stat -c %s "$PREFIX/bin/ffmpeg") bytes"
}

ABIS=${*:-arm64-v8a armeabi-v7a x86_64}
for a in $ABIS; do
  case $a in
    arm64-v8a)   build_one arm64-v8a aarch64 aarch64-linux-android armv8-a aarch64-linux-android ;;
    armeabi-v7a) build_one armeabi-v7a arm armv7a-linux-androideabi armv7-a arm-linux-androideabi ;;
    x86_64)      build_one x86_64 x86_64 x86_64-linux-android x86-64 x86_64-linux-android ;;
  esac
done
echo DONE
