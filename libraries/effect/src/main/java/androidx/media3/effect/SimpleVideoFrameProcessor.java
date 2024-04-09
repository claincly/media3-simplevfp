/*
 * Copyright 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.media3.effect;

import static androidx.media3.common.util.Assertions.checkArgument;
import static androidx.media3.common.util.Assertions.checkNotNull;
import static androidx.media3.common.util.Assertions.checkState;
import static androidx.media3.common.util.Assertions.checkStateNotNull;
import static androidx.media3.common.util.GlUtil.createFocusedEglContextWithFallback;
import static androidx.media3.effect.DefaultVideoFrameProcessor.checkColors;

import android.content.Context;
import android.graphics.Bitmap;
import android.opengl.EGL14;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.util.Pair;
import android.view.Surface;
import androidx.annotation.Nullable;
import androidx.media3.common.ColorInfo;
import androidx.media3.common.DebugViewProvider;
import androidx.media3.common.Effect;
import androidx.media3.common.FrameInfo;
import androidx.media3.common.GlObjectsProvider;
import androidx.media3.common.GlTextureInfo;
import androidx.media3.common.OnInputFrameProcessedListener;
import androidx.media3.common.SurfaceInfo;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.VideoFrameProcessor;
import androidx.media3.common.util.GlProgram;
import androidx.media3.common.util.GlUtil;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.Size;
import androidx.media3.common.util.TimestampIterator;
import androidx.media3.common.util.Util;
import com.google.common.util.concurrent.MoreExecutors;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

public class SimpleVideoFrameProcessor implements VideoFrameProcessor {

  private static final String TAG = "SimpleVFP";

  private final ColorInfo outputColorInfo;
  private final Context context;
  private final GlObjectsProvider glObjectsProvider;
  private final FrameRenderingShader frameRenderingShader;
  private final VideoFrameProcessingTaskExecutor videoFrameProcessingTaskExecutor;
  private final Listener listener;
  private final Executor listenerExecutor;
  private final ExternalTextureManager externalTextureManager;
  private final EGLDisplay eglDisplay;
  private final EGLContext eglContext;

  private @MonotonicNonNull FrameInfo frameInfo;
  private @MonotonicNonNull SimpleGlShaderProgramAdapter simpleGlShaderProgramAdapter;
  private boolean registeredInputStream;

  public static class Factory implements VideoFrameProcessor.Factory {

    private static final String THREAD_NAME = "SimpleVideoFrameProcessor";

    @Override
    public VideoFrameProcessor create(
        Context context,
        DebugViewProvider debugViewProvider,
        ColorInfo outputColorInfo,
        boolean renderFramesAutomatically,
        Executor listenerExecutor,
        Listener listener)
        throws VideoFrameProcessingException {
      ExecutorService executorService = Util.newSingleThreadExecutor(THREAD_NAME);
      try {
        return executorService
            .submit(
                () ->
                    new SimpleVideoFrameProcessor(
                        context,
                        outputColorInfo,
                        new VideoFrameProcessingTaskExecutor(
                            executorService,
                            /* shouldShutdownExecutorService= */ true,
                            listener::onError),
                        listenerExecutor,
                        listener))
            .get();
      } catch (ExecutionException e) {
        throw new VideoFrameProcessingException(e);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new VideoFrameProcessingException(e);
      }
    }
  }

  private SimpleVideoFrameProcessor(
      Context context,
      ColorInfo outputColorInfo,
      VideoFrameProcessingTaskExecutor videoFrameProcessingTaskExecutor,
      Executor listenerExecutor,
      Listener listener)
      throws VideoFrameProcessingException, GlUtil.GlException {
    this.glObjectsProvider = new DefaultGlObjectsProvider();
    this.eglDisplay = GlUtil.getDefaultEglDisplay();
    int[] configAttributes =
        ColorInfo.isTransferHdr(outputColorInfo)
            ? GlUtil.EGL_CONFIG_ATTRIBUTES_RGBA_1010102
            : GlUtil.EGL_CONFIG_ATTRIBUTES_RGBA_8888;
    this.eglContext =
        createFocusedEglContextWithFallback(glObjectsProvider, eglDisplay, configAttributes);
    this.outputColorInfo = outputColorInfo;
    this.externalTextureManager =
        new ExternalTextureManager(
            glObjectsProvider,
            videoFrameProcessingTaskExecutor,
            /* repeatLastRegisteredFrame= */ false);
    this.context = context;
    this.frameRenderingShader =
        new FrameRenderingShader(
            context,
            eglDisplay,
            eglContext,
            outputColorInfo,
            glObjectsProvider,
            listenerExecutor,
            listener);
    this.videoFrameProcessingTaskExecutor = videoFrameProcessingTaskExecutor;
    this.listener = listener;
    this.listenerExecutor = listenerExecutor;
  }

  @Override
  public Surface getInputSurface() {
    return externalTextureManager.getInputSurface();
  }

  @Override
  public void registerInputStream(
      @InputType int inputType, List<Effect> effects, FrameInfo frameInfo) {
    checkState(!registeredInputStream);
    this.frameInfo = frameInfo;
    checkArgument(effects.size() == 1, "SimpleVideoFrameProcessor takes only one SimpleGlEffect");
    Effect effect = effects.get(0);
    checkArgument(
        effect instanceof SimpleGlEffect,
        "SimpleVideoFrameProcessor takes only one SimpleGlEffect");
    videoFrameProcessingTaskExecutor.submit(() -> configureEffects((SimpleGlEffect) effect));
    registeredInputStream = true;
  }

  @Override
  public boolean registerInputFrame() {
    externalTextureManager.registerInputFrame(frameInfo);
    return true;
  }

  @Override
  public int getPendingInputFrameCount() {
    return externalTextureManager.getPendingFrameCount();
  }

  @Override
  public void setOutputSurfaceInfo(@Nullable SurfaceInfo outputSurfaceInfo) {
    if (outputSurfaceInfo == null) {
      return;
    }
    try {
      frameRenderingShader.setOutputSurfaceInfo(glObjectsProvider, outputSurfaceInfo);
    } catch (GlUtil.GlException e) {
      listenerExecutor.execute(() -> listener.onError(VideoFrameProcessingException.from(e)));
    }
  }

  @Override
  public void renderOutputFrame(long renderTimeNs) {
    videoFrameProcessingTaskExecutor.submitWithHighPriority(
        () -> frameRenderingShader.renderOutputFrame(renderTimeNs));
  }

  @Override
  public void signalEndOfInput() {
    externalTextureManager.signalEndOfCurrentInputStream();
  }

  @Override
  public void release() {
    try {
      try {
        externalTextureManager.release();
        frameRenderingShader.release();
        if (simpleGlShaderProgramAdapter != null) {
          simpleGlShaderProgramAdapter.release();
        }
      } catch (Exception e) {
        Log.e(TAG, "Error releasing shader program", e);
      }
    } finally {
      try {
        GlUtil.destroyEglContext(eglDisplay, eglContext);
      } catch (GlUtil.GlException e) {
        Log.e(TAG, "Error releasing GL context", e);
      }
    }
  }

  @Override
  public boolean queueInputTexture(int textureId, long presentationTimeUs) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void setOnInputFrameProcessedListener(OnInputFrameProcessedListener listener) {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean queueInputBitmap(Bitmap inputBitmap, TimestampIterator timestampIterator) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void flush() {
    throw new UnsupportedOperationException();
  }

  private void configureEffects(SimpleGlEffect simpleGlEffect)
      throws VideoFrameProcessingException {
    checkStateNotNull(frameInfo);
    checkColors(
        /* inputColorInfo= */ frameInfo.colorInfo,
        outputColorInfo,
        /* enableColorTransfers= */ false);

    simpleGlShaderProgramAdapter =
        new SimpleGlShaderProgramAdapter(
            simpleGlEffect.toGlShaderProgram(context, /* useHdr= */ false), frameRenderingShader);

    externalTextureManager.setSamplingGlShaderProgram(simpleGlShaderProgramAdapter);
    simpleGlShaderProgramAdapter.setInputListener(externalTextureManager);
  }

  private static final class SimpleGlShaderProgramAdapter
      implements ExternalShaderProgram, FrameRenderingShader.Listener {
    private final SimpleGlShaderProgram simpleGlShaderProgram;
    private final Queue<Pair<GlTextureInfo, Long>> pendingInputFrames;
    private final FrameRenderingShader frameRenderingShader;

    private @MonotonicNonNull InputListener inputListener;
    private ErrorListener errorListener;
    private Executor errorListenerExecutor;

    public SimpleGlShaderProgramAdapter(SimpleGlShaderProgram simpleGlShaderProgram,
        FrameRenderingShader frameRenderingShader) {
      this.simpleGlShaderProgram = simpleGlShaderProgram;
      this.pendingInputFrames = new ArrayDeque<>();
      this.frameRenderingShader = frameRenderingShader;
      frameRenderingShader.configure(simpleGlShaderProgram.getOutputSize());
      this.errorListener = e -> {};
      this.errorListenerExecutor = MoreExecutors.directExecutor();
      frameRenderingShader.setListener(this);
    }

    @Override
    public void setTextureTransformMatrix(float[] textureTransformMatrix) {
      simpleGlShaderProgram.setTextureTransformMatrix(textureTransformMatrix);
    }

    @Override
    public void setInputListener(InputListener inputListener) {
      this.inputListener = inputListener;
      inputListener.onReadyToAcceptInputFrame();
    }

    @Override
    public void setErrorListener(Executor executor, ErrorListener errorListener) {
      errorListenerExecutor = executor;
      this.errorListener = errorListener;
      simpleGlShaderProgram.setErrorListener(executor, errorListener);
    }

    @Override
    public void queueInputFrame(
        GlObjectsProvider glObjectsProvider, GlTextureInfo inputTexture, long presentationTimeUs) {
      if (frameRenderingShader.canAcceptInput()) {
        try {
          processInputFrame(
              inputTexture,
              /* outputTexture= */ frameRenderingShader.getInputTexture(),
              presentationTimeUs);
        } catch (VideoFrameProcessingException e) {
          errorListenerExecutor.execute(() -> errorListener.onError(e));
        }
      } else {
        pendingInputFrames.add(Pair.create(inputTexture, presentationTimeUs));
      }
    }

    @Override
    public void release() throws VideoFrameProcessingException {
      simpleGlShaderProgram.release();
    }

    @Override
    public void onReadyToAcceptInput() {
      // The frameRenderingShader can take input.
      if (pendingInputFrames.isEmpty()) {
        return;
      }
      Pair<GlTextureInfo, Long> inputFrame = checkNotNull(pendingInputFrames.poll());
      try {
        processInputFrame(
            /* inputTexture= */ inputFrame.first,
            /* outputTexture= */ frameRenderingShader.getInputTexture(),
            /* presentationTimeUs= */ inputFrame.second);
      } catch (VideoFrameProcessingException e) {
        errorListenerExecutor.execute(() -> errorListener.onError(e));
      }
    }

    private void processInputFrame(
        GlTextureInfo inputTexture, GlTextureInfo outputTexture, long presentationTimeUs)
        throws VideoFrameProcessingException {
      simpleGlShaderProgram.draw(inputTexture, outputTexture, presentationTimeUs);
      frameRenderingShader.commitTexture(outputTexture, presentationTimeUs);
      inputListener.onReadyToAcceptInputFrame();
      inputListener.onInputFrameProcessed(inputTexture);
    }

    @Override
    public void releaseOutputFrame(GlTextureInfo outputTexture) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void signalEndOfCurrentInputStream() {
      throw new UnsupportedOperationException();
    }

    @Override
    public void flush() {
      throw new UnsupportedOperationException();
    }

    @Override
    public void setOutputListener(OutputListener outputListener) {
      throw new UnsupportedOperationException();
    }
  }

  private static final class FrameRenderingShader {

    public interface Listener {
      void onReadyToAcceptInput();
    }

    private static final String VERTEX_SHADER_TRANSFORMATION_ES2_PATH =
        "shaders/vertex_shader_transformation_es2.glsl";
    private static final String FRAGMENT_SHADER_TRANSFORMATION_ES2_PATH =
        "shaders/fragment_shader_transformation_es2.glsl";
    private static final int OUTPUT_CAPACITY = 5;

    private final GlProgram outputRenderer;
    private final EGLDisplay eglDisplay;
    private final EGLContext eglContext;
    private final ColorInfo outputColorInfo;
    private final Queue<Pair<GlTextureInfo, Long>> availableFrames;
    private final GlObjectsProvider glObjectsProvider;
    private final Executor videoFrameProcessorListenerExecutor;
    private final VideoFrameProcessor.Listener videoFrameProcessorListener;
    private final TexturePool texturePool;

    private @MonotonicNonNull SurfaceInfo outputSurfaceInfo;
    private @MonotonicNonNull EGLSurface outputEglSurface;
    private @MonotonicNonNull  Listener listener;

    public FrameRenderingShader(
        Context context,
        EGLDisplay eglDisplay,
        EGLContext eglContext,
        ColorInfo outputColorInfo,
        GlObjectsProvider glObjectsProvider,
        Executor videoFrameProcessorListenerExecutor,
        VideoFrameProcessor.Listener videoFrameProcessorListener)
        throws VideoFrameProcessingException {
      this.eglDisplay = eglDisplay;
      this.eglContext = eglContext;
      this.outputColorInfo = outputColorInfo;
      this.glObjectsProvider = glObjectsProvider;
      this.videoFrameProcessorListenerExecutor = videoFrameProcessorListenerExecutor;
      this.videoFrameProcessorListener = videoFrameProcessorListener;
      this.availableFrames = new ConcurrentLinkedQueue<>();

      try {
        this.outputRenderer =
            new GlProgram(
                context,
                VERTEX_SHADER_TRANSFORMATION_ES2_PATH,
                FRAGMENT_SHADER_TRANSFORMATION_ES2_PATH);
      } catch (IOException | GlUtil.GlException e) {
        throw VideoFrameProcessingException.from(e);
      }

      float[] identityMatrix = GlUtil.create4x4IdentityMatrix();
      outputRenderer.setFloatsUniform("uTexTransformationMatrix", identityMatrix);
      outputRenderer.setFloatsUniform("uTransformationMatrix", identityMatrix);
      outputRenderer.setFloatsUniform("uRgbMatrix", identityMatrix);
      outputRenderer.setBufferAttribute(
          "aFramePosition",
          GlUtil.getNormalizedCoordinateBounds(),
          GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE);
      texturePool = new TexturePool(/* useHighPrecisionColorComponents= */ false, OUTPUT_CAPACITY);
    }

    public void configure(Size size) {
      checkState(!texturePool.isConfigured());
      try {
        texturePool.ensureConfigured(glObjectsProvider, size.getWidth(), size.getHeight());
      } catch (GlUtil.GlException e) {
        videoFrameProcessorListenerExecutor.execute(
            () -> videoFrameProcessorListener.onError(VideoFrameProcessingException.from(e)));
      }
    }

    public boolean canAcceptInput() {
      return texturePool.freeTextureCount() != 0;
    }

    public GlTextureInfo getInputTexture() {
      checkState(canAcceptInput());
      return texturePool.useTexture();
    }

    public void commitTexture(GlTextureInfo textureInfo, long presentationTimeUs) {
      videoFrameProcessorListenerExecutor.execute(
          () -> videoFrameProcessorListener.onOutputFrameAvailableForRendering(presentationTimeUs));
      availableFrames.add(Pair.create(textureInfo, presentationTimeUs));
    }

    public void setListener(Listener listener) {
      this.listener = listener;
    }

    public void setOutputSurfaceInfo(
        GlObjectsProvider glObjectsProvider, SurfaceInfo outputSurfaceInfo)
        throws GlUtil.GlException {
      checkState(this.outputSurfaceInfo == null);
      this.outputSurfaceInfo = outputSurfaceInfo;
      outputEglSurface =
          glObjectsProvider.createEglSurface(
              eglDisplay,
              outputSurfaceInfo.surface,
              outputColorInfo.colorTransfer,
              /* isEncoderInputSurface= */ false);
    }

    public void renderOutputFrame(long renderTimeNs) throws VideoFrameProcessingException {
      Pair<GlTextureInfo, Long> availableFrame = availableFrames.remove();
      GlTextureInfo inputTexture = availableFrame.first;
      if (renderTimeNs == VideoFrameProcessor.DROP_OUTPUT_FRAME) {
        finishTexture(inputTexture);
        return;
      }

      checkStateNotNull(outputSurfaceInfo);
      checkStateNotNull(outputEglSurface);
      try {
        GlUtil.focusEglSurface(
            eglDisplay,
            eglContext,
            outputEglSurface,
            outputSurfaceInfo.width,
            outputSurfaceInfo.height);
        GlUtil.clearFocusedBuffers();
        outputRenderer.use();
        outputRenderer.setSamplerTexIdUniform(
            "uTexSampler", inputTexture.texId, /* texUnitIndex= */ 0);
        outputRenderer.bindAttributesAndUniforms();
      } catch (GlUtil.GlException e) {
        throw VideoFrameProcessingException.from(e);
      }

      GLES20.glDrawArrays(
          GLES20.GL_TRIANGLE_STRIP,
          /* first= */ 0,
          /* count= */ GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE);
      EGLExt.eglPresentationTimeANDROID(
          eglDisplay,
          outputEglSurface,
          renderTimeNs == VideoFrameProcessor.RENDER_OUTPUT_FRAME_IMMEDIATELY
              ? System.nanoTime()
              : renderTimeNs);
      EGL14.eglSwapBuffers(eglDisplay, outputEglSurface);
      finishTexture(inputTexture);
    }

    public void release() throws VideoFrameProcessingException {
      try {
        GlUtil.destroyEglSurface(eglDisplay, outputEglSurface);
        GlUtil.checkGlError();
      } catch (GlUtil.GlException e) {
        throw new VideoFrameProcessingException(e);
      }
    }

    /** Returns texture to the pool and notify can take another frame. */
    private void finishTexture(GlTextureInfo textureInfo) {
      texturePool.freeTexture(textureInfo);
      listener.onReadyToAcceptInput();
    }
  }
}
