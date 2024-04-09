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

import android.content.Context;
import android.opengl.GLES20;
import androidx.media3.common.GlTextureInfo;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.util.GlProgram;
import androidx.media3.common.util.GlUtil;
import androidx.media3.common.util.Size;
import java.io.IOException;

public class SimpleCopy extends SimpleGlShaderProgram {

  private static final String VERTEX_SHADER_PATH = "shaders/vertex_shader_transformation_es2.glsl";
  private static final String FRAGMENT_SHADER_PATH = "shaders/fragment_shader_copy_es2_simple.glsl";

  private final GlProgram glProgram;

  public SimpleCopy(Context context, Size outputSize) throws VideoFrameProcessingException {
    super(outputSize);
    try {
      glProgram = new GlProgram(context, VERTEX_SHADER_PATH, FRAGMENT_SHADER_PATH);
    } catch (IOException | GlUtil.GlException e) {
      throw new VideoFrameProcessingException(e);
    }

    float[] identityMatrix = GlUtil.create4x4IdentityMatrix();
    glProgram.setFloatsUniform("uTransformationMatrix", identityMatrix);
    glProgram.setBufferAttribute(
        "aFramePosition",
        GlUtil.getNormalizedCoordinateBounds(),
        GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE);

  }

  @Override
  public void setTextureTransformMatrix(float[] textureTransformMatrix) {
    glProgram.setFloatsUniform("uTexTransformationMatrix", textureTransformMatrix);
  }

  @Override
  public void draw(
      GlTextureInfo inputTexture, GlTextureInfo outputTexture, long presentationTimeUs) {
    try {
      // Specify drawing onto output texture
      focusOutputTexture(outputTexture);

      // Use the GlProgram, set input
      glProgram.use();
      glProgram.setSamplerTexIdUniform("uTexSampler", inputTexture.texId, /* texUnitIndex= */ 0);
      glProgram.bindAttributesAndUniforms();

      // Draw
      GLES20.glDrawArrays(
          GLES20.GL_TRIANGLE_STRIP,
          /* first= */ 0,
          /* count= */ GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE);
    } catch (GlUtil.GlException e) {
      errorExecutor.execute(() -> errorListener.onError(VideoFrameProcessingException.from(e)));
    }
  }

  @Override
  public void release() {
    try {
      glProgram.delete();
    } catch (GlUtil.GlException e) {
      errorExecutor.execute(() -> errorListener.onError(VideoFrameProcessingException.from(e)));
    }
  }
}
