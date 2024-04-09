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

import androidx.media3.common.GlObjectsProvider;
import androidx.media3.common.GlTextureInfo;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.util.GlUtil;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.Size;
import com.google.common.util.concurrent.MoreExecutors;
import java.util.concurrent.Executor;

public abstract class SimpleGlShaderProgram implements ExternalShaderProgram {

  protected final Size outputSize;
  protected Executor errorExecutor;
  protected ErrorListener errorListener;

  public SimpleGlShaderProgram(Size outputSize) {
    this.outputSize = outputSize;
    errorExecutor = MoreExecutors.directExecutor();
    errorListener = e -> Log.e("SimpleCopy", "Error: ", e);
  }

  @Override
  public abstract void setTextureTransformMatrix(float[] textureTransformMatrix);

  public abstract void draw(
      GlTextureInfo inputTexture,
      GlTextureInfo outputTexture,
      long presentationTimeUs);

  @Override
  public void setErrorListener(Executor executor, ErrorListener errorListener) {
    this.errorExecutor = executor;
    this.errorListener = errorListener;
  }

  @Override
  public abstract void release() throws VideoFrameProcessingException;

  public final Size getOutputSize() {
    return outputSize;
  }

  /* package */ static void focusOutputTexture(GlTextureInfo outputTexture)
      throws GlUtil.GlException {
    GlUtil.focusFramebufferUsingCurrentContext(
        outputTexture.fboId, outputTexture.width, outputTexture.height);
    GlUtil.clearFocusedBuffers();
  }

  @Override
  public void setInputListener(InputListener inputListener) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void setOutputListener(OutputListener outputListener) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void queueInputFrame(GlObjectsProvider glObjectsProvider, GlTextureInfo inputTexture,
      long presentationTimeUs) {
    throw new UnsupportedOperationException();
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
}
