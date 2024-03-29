package com.pedro.encoder.video;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.util.Pair;
import android.view.Surface;
import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import com.pedro.encoder.input.video.FpsLimiter;
import com.pedro.encoder.input.video.Frame;
import com.pedro.encoder.input.video.GetCameraData;
import com.pedro.encoder.utils.CodecUtil;
import com.pedro.encoder.utils.yuv.YUVUtil;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Created by pedro on 12/09/19.
 *
 * Under develop. Only Surface to Surface mode working for now.
 */
@RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
public class VideoEncoderAsync extends MediaCodec.Callback implements GetCameraData {

  private String TAG = "VideoEncoder";
  private MediaCodec videoEncoder;
  private GetVideoData getVideoData;
  private long presentTimeUs;
  private volatile boolean running = false;
  private boolean spsPpsSetted = false;
  private boolean hardwareRotation = false;

  //surface to buffer encoder
  private Surface inputSurface;
  //buffer to buffer, 3 queue to optimize frames on rotation
  private BlockingQueue<Frame> queue = new LinkedBlockingQueue<>(80);

  //default parameters for encoder
  private CodecUtil.Force force = CodecUtil.Force.FIRST_COMPATIBLE_FOUND;
  private int width = 640;
  private int height = 480;
  private int fps = 30;
  private int bitRate = 1200 * 1024; //in kbps
  private int rotation = 90;
  private int iFrameInterval = 2;
  private FormatVideoEncoder formatVideoEncoder = FormatVideoEncoder.YUV420Dynamical;
  //for disable video
  private boolean sendBlackImage = false;
  private byte[] blackImage;
  private FpsLimiter fpsLimiter = new FpsLimiter();
  private String type = CodecUtil.H264_MIME;

  public VideoEncoderAsync(GetVideoData getVideoData) {
    this.getVideoData = getVideoData;
  }

  /**
   * Prepare encoder with custom parameters
   */
  public boolean prepareVideoEncoder(int width, int height, int fps, int bitRate, int rotation,
      boolean hardwareRotation, int iFrameInterval, FormatVideoEncoder formatVideoEncoder) {
    this.width = width;
    this.height = height;
    this.fps = fps;
    this.bitRate = bitRate;
    this.rotation = rotation;
    this.hardwareRotation = hardwareRotation;
    this.formatVideoEncoder = formatVideoEncoder;
    MediaCodecInfo encoder = chooseVideoEncoder(type);
    try {
      if (encoder != null) {
        videoEncoder = MediaCodec.createByCodecName(encoder.getName());
        if (this.formatVideoEncoder == FormatVideoEncoder.YUV420Dynamical) {
          this.formatVideoEncoder = chooseColorDynamically(encoder);
          if (this.formatVideoEncoder == null) {
            Log.e(TAG, "YUV420 dynamical choose failed");
            return false;
          }
        }
      } else {
        Log.e(TAG, "Valid encoder not found");
        return false;
      }
      MediaFormat videoFormat;
      //if you dont use mediacodec rotation you need swap width and height in rotation 90 or 270
      // for correct encoding resolution
      String resolution;
      if (!hardwareRotation && (rotation == 90 || rotation == 270)) {
        resolution = height + "x" + width;
        videoFormat = MediaFormat.createVideoFormat(type, height, width);
      } else {
        resolution = width + "x" + height;
        videoFormat = MediaFormat.createVideoFormat(type, width, height);
      }
      Log.i(TAG, "Prepare video info: " + this.formatVideoEncoder.name() + ", " + resolution);
      videoFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT,
          this.formatVideoEncoder.getFormatCodec());
      videoFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 0);
      videoFormat.setInteger(MediaFormat.KEY_BIT_RATE, bitRate);
      videoFormat.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
      videoFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, iFrameInterval);
      if (hardwareRotation) {
        videoFormat.setInteger("rotation-degrees", rotation);
      }
      videoEncoder.setCallback(this);
      videoEncoder.configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
      running = false;
      if (formatVideoEncoder == FormatVideoEncoder.SURFACE
          && Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
        inputSurface = videoEncoder.createInputSurface();
      }
      prepareBlackImage();
      return true;
    } catch (IOException | IllegalStateException e) {
      Log.e(TAG, "Create VideoEncoder failed.", e);
      return false;
    }
  }

  private FormatVideoEncoder chooseColorDynamically(MediaCodecInfo mediaCodecInfo) {
    for (int color : mediaCodecInfo.getCapabilitiesForType(CodecUtil.H264_MIME).colorFormats) {
      if (color == FormatVideoEncoder.YUV420PLANAR.getFormatCodec()) {
        return FormatVideoEncoder.YUV420PLANAR;
      } else if (color == FormatVideoEncoder.YUV420SEMIPLANAR.getFormatCodec()) {
        return FormatVideoEncoder.YUV420SEMIPLANAR;
      }
    }
    return null;
  }

  /**
   * Prepare encoder with default parameters
   */
  public boolean prepareVideoEncoder() {
    return prepareVideoEncoder(width, height, fps, bitRate, rotation, false, iFrameInterval,
        formatVideoEncoder);
  }

  public void setVideoBitrateOnFly(int bitrate) {
    if (isRunning()) {
      this.bitRate = bitrate;
      Bundle bundle = new Bundle();
      bundle.putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bitrate);
      try {
        videoEncoder.setParameters(bundle);
      } catch (IllegalStateException e) {
        Log.e(TAG, "encoder need be running", e);
      }
    }
  }

  public void forceSyncFrame() {
    if (isRunning()) {
      Bundle bundle = new Bundle();
      bundle.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
      try {
        videoEncoder.setParameters(bundle);
      } catch (IllegalStateException e) {
        Log.e(TAG, "encoder need be running", e);
      }
    }
  }

  public void setForce(CodecUtil.Force force) {
    this.force = force;
  }

  public Surface getInputSurface() {
    return inputSurface;
  }

  public void setInputSurface(Surface inputSurface) {
    this.inputSurface = inputSurface;
  }

  public int getWidth() {
    return width;
  }

  public int getHeight() {
    return height;
  }

  public boolean isHardwareRotation() {
    return hardwareRotation;
  }

  public boolean isRunning() {
    return running;
  }

  public int getRotation() {
    return rotation;
  }

  public void setFps(int fps) {
    this.fps = fps;
  }

  public int getFps() {
    return fps;
  }

  public void start() {
    start(true);
  }

  public int getBitRate() {
    return bitRate;
  }

  public String getType() {
    return type;
  }

  public void setType(String type) {
    this.type = type;
  }

  public void start(boolean resetTs) {
    spsPpsSetted = false;
    if (resetTs) {
      presentTimeUs = System.nanoTime() / 1000;
      fpsLimiter.setFPS(fps);
    }
    YUVUtil.preAllocateBuffers(width * height * 3 / 2);
    running = true;
    videoEncoder.start();
  }

  public void stop() {
    running = false;
    if (videoEncoder != null) {
      videoEncoder.stop();
      videoEncoder.release();
      videoEncoder = null;
    }
    queue.clear();
    spsPpsSetted = false;
    inputSurface = null;
  }

  public void reset() {
    stop();
    prepareVideoEncoder(width, height, fps, bitRate, rotation, hardwareRotation, iFrameInterval,
        formatVideoEncoder);
    start(false);
  }

  @Override
  public void inputYUVData(final Frame frame) {
    try {
      queue.add(frame);
    } catch (IllegalStateException e) {
      Log.i(TAG, "frame discarded");
    }
  }

  private void sendSPSandPPS(MediaFormat mediaFormat) {
    //H265
    if (type.equals(CodecUtil.H265_MIME)) {
      List<ByteBuffer> byteBufferList =
          extractVpsSpsPpsFromH265(mediaFormat.getByteBuffer("csd-0"));
      getVideoData.onSpsPpsVps(byteBufferList.get(1), byteBufferList.get(2), byteBufferList.get(0));
      //H264
    } else {
      getVideoData.onSpsPps(mediaFormat.getByteBuffer("csd-0"), mediaFormat.getByteBuffer("csd-1"));
    }
  }

  /**
   * choose the video encoder by mime.
   */
  private MediaCodecInfo chooseVideoEncoder(String mime) {
    List<MediaCodecInfo> mediaCodecInfoList;
    if (force == CodecUtil.Force.HARDWARE) {
      mediaCodecInfoList = CodecUtil.getAllHardwareEncoders(mime);
    } else if (force == CodecUtil.Force.SOFTWARE) {
      mediaCodecInfoList = CodecUtil.getAllSoftwareEncoders(mime);
    } else {
      mediaCodecInfoList = CodecUtil.getAllEncoders(mime);
    }
    for (MediaCodecInfo mci : mediaCodecInfoList) {
      Log.i(TAG, String.format("VideoEncoder %s", mci.getName()));
      MediaCodecInfo.CodecCapabilities codecCapabilities = mci.getCapabilitiesForType(mime);
      for (int color : codecCapabilities.colorFormats) {
        Log.i(TAG, "Color supported: " + color);
        if (formatVideoEncoder == FormatVideoEncoder.SURFACE) {
          if (color == FormatVideoEncoder.SURFACE.getFormatCodec()) return mci;
        } else {
          //check if encoder support any yuv420 color
          if (color == FormatVideoEncoder.YUV420PLANAR.getFormatCodec()
              || color == FormatVideoEncoder.YUV420SEMIPLANAR.getFormatCodec()) {
            return mci;
          }
        }
      }
    }
    return null;
  }

  private void prepareBlackImage() {
    Bitmap b = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
    Canvas canvas = new Canvas(b);
    canvas.drawColor(Color.BLACK);
    int x = b.getWidth();
    int y = b.getHeight();
    int[] data = new int[x * y];
    b.getPixels(data, 0, x, 0, 0, x, y);
    blackImage = YUVUtil.ARGBtoYUV420SemiPlanar(data, width, height);
  }

  public void startSendBlackImage() {
    sendBlackImage = true;
    if (Build.VERSION.SDK_INT >= 19) {
      if (isRunning()) {
        Bundle bundle = new Bundle();
        bundle.putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, 100 * 1024);
        try {
          videoEncoder.setParameters(bundle);
        } catch (IllegalStateException e) {
          Log.e(TAG, "encoder need be running", e);
        }
      }
    }
  }

  public void stopSendBlackImage() {
    sendBlackImage = false;
    if (Build.VERSION.SDK_INT >= 19) {
      setVideoBitrateOnFly(bitRate);
    }
  }

  /**
   * decode sps and pps if the encoder never call to MediaCodec.INFO_OUTPUT_FORMAT_CHANGED
   */
  private Pair<ByteBuffer, ByteBuffer> decodeSpsPpsFromBuffer(ByteBuffer outputBuffer, int length) {
    byte[] mSPS = null, mPPS = null;
    byte[] csd = new byte[length];
    outputBuffer.get(csd, 0, length);
    int i = 0;
    int spsIndex = -1;
    int ppsIndex = -1;
    while (i < length - 4) {
      if (csd[i] == 0 && csd[i + 1] == 0 && csd[i + 2] == 0 && csd[i + 3] == 1) {
        if (spsIndex == -1) {
          spsIndex = i;
        } else {
          ppsIndex = i;
          break;
        }
      }
      i++;
    }
    if (spsIndex != -1 && ppsIndex != -1) {
      mSPS = new byte[ppsIndex];
      System.arraycopy(csd, spsIndex, mSPS, 0, ppsIndex);
      mPPS = new byte[length - ppsIndex];
      System.arraycopy(csd, ppsIndex, mPPS, 0, length - ppsIndex);
    }
    if (mSPS != null && mPPS != null) {
      return new Pair<>(ByteBuffer.wrap(mSPS), ByteBuffer.wrap(mPPS));
    }
    return null;
  }

  /**
   * You need find 0 0 0 1 byte sequence that is the initiation of vps, sps and pps
   * buffers.
   *
   * @param csd0byteBuffer get in mediacodec case MediaCodec.INFO_OUTPUT_FORMAT_CHANGED
   * @return list with vps, sps and pps
   */
  private List<ByteBuffer> extractVpsSpsPpsFromH265(ByteBuffer csd0byteBuffer) {
    List<ByteBuffer> byteBufferList = new ArrayList<>();
    int vpsPosition = -1;
    int spsPosition = -1;
    int ppsPosition = -1;
    int contBufferInitiation = 0;
    byte[] csdArray = csd0byteBuffer.array();
    for (int i = 0; i < csdArray.length; i++) {
      if (contBufferInitiation == 3 && csdArray[i] == 1) {
        if (vpsPosition == -1) {
          vpsPosition = i - 3;
        } else if (spsPosition == -1) {
          spsPosition = i - 3;
        } else {
          ppsPosition = i - 3;
        }
      }
      if (csdArray[i] == 0) {
        contBufferInitiation++;
      } else {
        contBufferInitiation = 0;
      }
    }
    byte[] vps = new byte[spsPosition];
    byte[] sps = new byte[ppsPosition - spsPosition];
    byte[] pps = new byte[csdArray.length - ppsPosition];
    for (int i = 0; i < csdArray.length; i++) {
      if (i < spsPosition) {
        vps[i] = csdArray[i];
      } else if (i < ppsPosition) {
        sps[i - spsPosition] = csdArray[i];
      } else {
        pps[i - ppsPosition] = csdArray[i];
      }
    }
    byteBufferList.add(ByteBuffer.wrap(vps));
    byteBufferList.add(ByteBuffer.wrap(sps));
    byteBufferList.add(ByteBuffer.wrap(pps));
    return byteBufferList;
  }

  @Override
  public void onInputBufferAvailable(@NonNull MediaCodec mediaCodec, int inBufferIndex) {
    ByteBuffer bb = mediaCodec.getInputBuffer(inBufferIndex);
    boolean processed = false;
    while (!processed) {
      try {
        Frame frame = queue.poll(100, TimeUnit.MILLISECONDS);
        if (frame != null) {
          //if (fpsLimiter.limitFPS()) continue;
          byte[] buffer = frame.getBuffer();
          boolean isYV12 = frame.getFormat() == ImageFormat.YV12;
          if (!hardwareRotation) {
            int orientation = frame.isFlip() ? frame.getOrientation() + 180 : frame.getOrientation();
            if (orientation >= 360) orientation -= 360;
            buffer = isYV12 ? YUVUtil.rotateYV12(buffer, width, height, orientation)
                : YUVUtil.rotateNV21(buffer, width, height, orientation);
          }
          buffer = (sendBlackImage) ? blackImage
              : isYV12 ? YUVUtil.YV12toYUV420byColor(buffer, width, height, formatVideoEncoder)
                  : YUVUtil.NV21toYUV420byColor(buffer, width, height, formatVideoEncoder);
          bb.clear();
          bb.put(buffer, 0, buffer.length);
          long pts = System.nanoTime() / 1000 - presentTimeUs;
          mediaCodec.queueInputBuffer(inBufferIndex, 0, buffer.length, pts, 0);
          processed = true;
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }

  @Override
  public void onOutputBufferAvailable(@NonNull MediaCodec mediaCodec, int outBufferIndex,
      @NonNull MediaCodec.BufferInfo bufferInfo) {
    ByteBuffer bb = mediaCodec.getOutputBuffer(outBufferIndex);
    if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
      if (!spsPpsSetted) {
        Pair<ByteBuffer, ByteBuffer> buffers =
            decodeSpsPpsFromBuffer(bb.duplicate(), bufferInfo.size);
        if (buffers != null) {
          getVideoData.onSpsPps(buffers.first, buffers.second);
          spsPpsSetted = true;
        }
      }
    }
    bufferInfo.presentationTimeUs = System.nanoTime() / 1000 - presentTimeUs;
    getVideoData.getVideoData(bb, bufferInfo);
    mediaCodec.releaseOutputBuffer(outBufferIndex, false);
  }

  @Override
  public void onError(@NonNull MediaCodec mediaCodec, @NonNull MediaCodec.CodecException e) {
    Log.e(TAG, "Error", e);
  }

  @Override
  public void onOutputFormatChanged(@NonNull MediaCodec mediaCodec,
      @NonNull MediaFormat mediaFormat) {
    getVideoData.onVideoFormat(mediaFormat);
    sendSPSandPPS(mediaFormat);
    spsPpsSetted = true;
  }
}