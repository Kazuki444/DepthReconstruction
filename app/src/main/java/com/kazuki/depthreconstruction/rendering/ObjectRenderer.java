package com.kazuki.depthreconstruction.rendering;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.opengl.GLES20;
import android.opengl.GLES30;
import android.opengl.GLUtils;
import android.opengl.Matrix;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.util.Map;
import java.util.TreeMap;

import javax.microedition.khronos.opengles.GL;

import de.javagl.obj.Obj;
import de.javagl.obj.ObjData;
import de.javagl.obj.ObjReader;
import de.javagl.obj.ObjUtils;

public class ObjectRenderer {
  private static final String TAG = ObjectRenderer.class.getSimpleName();

  public enum BlendMode {
    Shadow,
    AlphaBlending
  }

  // Shader names.
  private static final String VERTEX_SHADER_NAME = "shaders/ar_object.vert";
  private static final String FRAGMENT_SHADER_NAME = "shaders/ar_object.frag";

  private static final int COORDS_PER_VERTEX = 3;
  private static final float[] DEFAULT_COLOR = new float[]{0f, 0f, 0f};

  private static final float[] LIGHT_DIRECTION = new float[]{0.250f, 0.866f, 0.433f, 0.0f};
  private final float[] viewLightDirection = new float[4];

  // Object vertex buffer variables
  private int vertexBufferId;
  private int verticesBaseAddress;
  private int texCoordsBaseAddress;
  private int normalsBaseAddress;
  private int indexBufferId;
  private int indexCount;

  private int program;
  private final int[] textures = new int[1];

  // Shader location: model view projection matrix
  private int modelViewUniform;
  private int modelViewProjectionUniform;

  // Shader location: object attributes
  private int positionAttribute;
  private int normalAttribute;
  private int texCoordAttribute;

  // Shader location: texture sampler
  private int textureUniform;

  // Shader location: environment properties
  private int lightingParametersUniform;

  // Shader location: material properties
  private int materialParametersUniform;

  // Shader location: color correction property
  private int colorCorrectionParameterUniform;

  // Shader location: object color property (to change the primary color of the object)
  private int colorUniform;

  // Shader location: depth texture
  private int depthTextureUniform;

  // Shader location: transform to depth uvs;
  private int depthUvTransformUniform;

  // Shader location: the aspect ratio of the depth texture
  private int depthAspectRatioUniform;

  private BlendMode blendMode = null;

  // Temporary matrices allocated here to reduce number of allocations for each frame
  private final float[] modelMatrix = new float[16];
  private final float[] modelViewMatrix = new float[16];
  private final float[] modelViewProjectionMatrix = new float[16];

  // Set default material properties to use for lighting
  private float ambient = 0.3f;
  private float diffuse = 1.0f;
  private float specular = 1.0f;
  private float specularPower = 6.0f;

  // Depth-for-Occlusion parameters
  private static final String USE_DEPTH_FOR_OCCLUSION_SHADER_FRAG = "USE_DEPTH_FOR_OCCLUSION";
  private boolean useDepthForOcclusion = false;
  private float depthAspectRatio = 0.0f;
  private float[] uvTransform = null;
  private int depthTextureId;


  /**
   * Create and initializes OpenGL resources needs for rendering the model
   *
   * @param context                 Context for loading shader and below-named model and texture assets
   * @param objAssetName            Name of the OBJ file containing the model geometry
   * @param diffuseTextureAssetName Name of the PGN file containing the diffuse texture map
   * @throws IOException
   */
  public void createOnGlThread(Context context, String objAssetName, String diffuseTextureAssetName)
          throws IOException {
    // Compiles and load the shader based on the current configuration
    compileAndLoadShaderProgram(context);

    // Read the texture
    Bitmap textureBitmap = BitmapFactory.decodeStream(context.getAssets().open(diffuseTextureAssetName));

    GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
    GLES30.glGenTextures(textures.length, textures, 0);
    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textures[0]);

    GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR);
    GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
    GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, textureBitmap, 0);
    GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D);
    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0);

    textureBitmap.recycle();

    ShaderUtil.checkGLError(TAG, "Texture loading");

    // Read the obj file
    InputStream objInputStream = context.getAssets().open(objAssetName);
    Obj obj = ObjReader.read(objInputStream);

    // Prepare the Obj so that its structure is suitable for rendering with OpenGL
    // 1. Triangulate it
    // 2. Make sure that texture coordinates are not ambiguous
    // 3. Make sure that normals are not ambiguous
    // 4. Convert it to single-indexed data
    obj = ObjUtils.convertToRenderable(obj);

    // OpenGL does not use Java arrays.
    // ByteBuffers are used instead of to provide data in a format that OpenGL understand

    // Obtain the data form the OBJ, as direct buffers:
    IntBuffer wideIndices = ObjData.getFaceVertexIndices(obj, 3);
    FloatBuffer vertices = ObjData.getVertices(obj);
    FloatBuffer texCoords = ObjData.getTexCoords(obj, 2);
    FloatBuffer normals = ObjData.getNormals(obj);

    // Convert int indices to shorts for GLES compatibility
    ShortBuffer indices = ByteBuffer.allocateDirect(2 * wideIndices.limit())
            .order(ByteOrder.nativeOrder())
            .asShortBuffer();
    while (wideIndices.hasRemaining()) {
      indices.put((short) wideIndices.get());
    }
    indices.rewind();

    int[] buffers = new int[2];
    GLES30.glGenBuffers(2, buffers, 0);
    vertexBufferId = buffers[0];
    indexBufferId = buffers[1];

    // Load vertex buffer
    verticesBaseAddress = 0;
    texCoordsBaseAddress = verticesBaseAddress + 4 * vertices.limit();
    normalsBaseAddress = texCoordsBaseAddress + 4 * texCoords.limit();
    final int totalBytes = normalsBaseAddress + 4 * normals.limit();

    GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vertexBufferId);
    GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, totalBytes, null, GLES30.GL_STATIC_DRAW);
    GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, verticesBaseAddress, 4 * vertices.limit(), vertices);
    GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, texCoordsBaseAddress, 4 * texCoords.limit(), texCoords);
    GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, normalsBaseAddress, 4 * normals.limit(), normals);
    GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0);

    // Load index buffer
    GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, indexBufferId);
    indexCount = indices.limit();
    GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, 2 * indexCount, indices, GLES30.GL_STATIC_DRAW);
    GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, 0);

    ShaderUtil.checkGLError(TAG, "OBJ buffer load");

    Matrix.setIdentityM(modelMatrix, 0);
  }

  /**
   * Select the blending mode for rendering
   *
   * @param blendMode The blending mode. Null indicates no blending(opaque blending).
   */
  public void setBlendMode(BlendMode blendMode) {
    this.blendMode = blendMode;
  }

  /**
   * Specifies whether to use the depth texture to perform depth-based occlusion of virtual objects
   * from real-world geometry.
   *
   * @param context              Context for Loading the shader
   * @param useDepthForOcclusion Specifies whether to use the depth texture to perform occlusion
   *                             during rendering of virtual objects.
   * @throws IOException
   */
  public void setUseDepthForOcclusion(Context context, boolean useDepthForOcclusion)
          throws IOException {
    if (this.useDepthForOcclusion == useDepthForOcclusion) {
      return;
    }

    // Toggles the occlusion rendering mode and recompiles the shader
    this.useDepthForOcclusion = useDepthForOcclusion;
    compileAndLoadShaderProgram(context);
  }

  private void compileAndLoadShaderProgram(Context context) throws IOException {
    // Compiles and loads the shader program based on the selected mode
    Map<String, Integer> defineValuesMap = new TreeMap<>();
    defineValuesMap.put(USE_DEPTH_FOR_OCCLUSION_SHADER_FRAG, useDepthForOcclusion ? 1 : 0);

    final int vertexShader =
            ShaderUtil.loadGLShader(TAG, context, GLES30.GL_VERTEX_SHADER, VERTEX_SHADER_NAME);
    final int fragmentShader =
            ShaderUtil.loadGLShader(TAG, context, GLES30.GL_FRAGMENT_SHADER, FRAGMENT_SHADER_NAME, defineValuesMap);

    program = GLES30.glCreateProgram();
    GLES30.glAttachShader(program, vertexShader);
    GLES30.glAttachShader(program, fragmentShader);
    GLES30.glLinkProgram(program);
    GLES30.glUseProgram(program);

    ShaderUtil.checkGLError(TAG, "program creation");

    modelViewUniform = GLES30.glGetUniformLocation(program, "u_ModelView");
    modelViewProjectionUniform = GLES30.glGetUniformLocation(program, "u_ModelViewProjection");

    positionAttribute = GLES30.glGetAttribLocation(program, "a_Position");
    normalAttribute = GLES30.glGetAttribLocation(program, "u_Normal");
    texCoordAttribute = GLES30.glGetAttribLocation(program, "a_TexCoord");

    textureUniform = GLES30.glGetUniformLocation(program, "u_Texture");

    lightingParametersUniform = GLES30.glGetUniformLocation(program, "u_LightingParameters");
    materialParametersUniform = GLES30.glGetUniformLocation(program, "u_MaterialParameters");
    colorCorrectionParameterUniform = GLES30.glGetUniformLocation(program, "u_ColorCorrectionParameters");
    colorUniform = GLES30.glGetUniformLocation(program, "u_ObjColor");

    // Occlusion Uniform
    if (useDepthForOcclusion) {
      depthTextureUniform = GLES30.glGetUniformLocation(program, "u_DepthTexture");
      depthUvTransformUniform = GLES30.glGetUniformLocation(program, "u_DepthUvTransform");
      depthAspectRatioUniform = GLES30.glGetUniformLocation(program, "u_DepthAspectRatio");
    }

    ShaderUtil.checkGLError(TAG, "Program parameters");
  }

  /**
   * Updates the object model matrix and applies scaling
   */
  public void updateModelMatrix(float[] modelMatrix, float scaleFactor) {
    float[] scaleMatrix = new float[16];
    Matrix.setIdentityM(scaleMatrix, 0);
    scaleMatrix[0] = scaleFactor;
    scaleMatrix[5] = scaleFactor;
    scaleMatrix[10] = scaleFactor;
    Matrix.multiplyMM(this.modelMatrix, 0, modelMatrix, 0, scaleMatrix, 0);
  }


  /**
   * Sets the surface characteristics of the rendered model
   */
  public void setMaterialProperties(float ambient, float diffuse, float specular, float specularPower) {
    this.ambient = ambient;
    this.diffuse = diffuse;
    this.specular = specular;
    this.specularPower = specularPower;
  }

  /**
   * draw the model
   */
  public void draw(float[] cameraView, float[] cameraPerspective, float[] colorCorrectionRgba) {
    draw(cameraView, cameraPerspective, colorCorrectionRgba, DEFAULT_COLOR);
  }

  public void draw(
          float[] cameraView,
          float[] cameraPerspective,
          float[] colorCorrectionRgba,
          float[] objColor) {

    ShaderUtil.checkGLError(TAG, "Before draw");

    // Build the ModelView and ModelViewProjection matrices for calculating object position and light
    Matrix.multiplyMM(modelViewMatrix, 0, cameraView, 0, modelMatrix, 0);
    Matrix.multiplyMM(modelViewProjectionMatrix, 0, cameraPerspective, 0, modelViewMatrix, 0);

    // Set the lighting environment properties
    Matrix.multiplyMM(viewLightDirection, 0, modelViewMatrix, 0, LIGHT_DIRECTION, 0);
    normalizeVec3(viewLightDirection);
    GLES30.glUniform4f(
            lightingParametersUniform,
            viewLightDirection[0],
            viewLightDirection[1],
            viewLightDirection[2],
            1.f
    );
    GLES30.glUniform4fv(colorCorrectionParameterUniform, 1, colorCorrectionRgba, 0);

    // Set the object color property
    GLES30.glUniform4fv(colorUniform, 1, objColor, 0);

    // Set the object material properties
    GLES30.glUniform4f(materialParametersUniform, ambient, diffuse, specular, specularPower);

    // Attach the object texture
    GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textures[0]);
    GLES30.glUniform1i(textureUniform, 0);

    // Occlusion parameters
    if (useDepthForOcclusion) {
      // Attach the depth texture
      GLES30.glActiveTexture(GLES30.GL_TEXTURE1);
      GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, depthTextureId);
      GLES30.glUniform1i(depthTextureUniform, 1);

      // Set the depth texture uv transform
      GLES30.glUniformMatrix3fv(depthUvTransformUniform, 1, false, uvTransform, 0);
      GLES30.glUniform1f(depthAspectRatioUniform, depthAspectRatio);
    }

    // Set the vertex attributes
    GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, vertexBufferId);

    GLES30.glVertexAttribPointer(
            positionAttribute, COORDS_PER_VERTEX, GLES30.GL_FLOAT, false, 0, verticesBaseAddress);
    GLES30.glVertexAttribPointer(normalAttribute, 3, GLES30.GL_FLOAT, false, 0, normalsBaseAddress);
    GLES30.glVertexAttribPointer(texCoordAttribute, 2, GLES30.GL_FLOAT, false, 0, texCoordsBaseAddress);

    GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0);

    // Set the modelViewProjection matrix in the shader
    GLES30.glUniformMatrix4fv(modelViewUniform, 1, false, modelViewMatrix, 0);
    GLES30.glUniformMatrix4fv(modelViewProjectionUniform, 1, false, modelViewProjectionMatrix, 0);

    // Enable vertex arrays
    GLES30.glEnableVertexAttribArray(positionAttribute);
    GLES30.glEnableVertexAttribArray(normalAttribute);
    GLES30.glEnableVertexAttribArray(texCoordAttribute);

    if (blendMode != null) {
      GLES30.glEnable(GLES30.GL_BLEND);
      switch (blendMode) {
        case Shadow:
          // Multiplicative blending function for shadow
          GLES30.glDepthMask(false);
          GLES30.glBlendFunc(GLES30.GL_ZERO, GLES30.GL_ONE_MINUS_SRC_ALPHA);
          break;
        case AlphaBlending:
          // Alpha blending function ,with the depth mask enabled
          GLES30.glDepthMask(true);

          // Textures are loaded with premultiplied alpha
          // (https://developer.android.com/reference/android/graphics/BitmapFactory.Options#inPremultiplied),
          // so we use the premultiplied alpha blend factors.
          GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA);
          break;
      }
    }

    GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, indexBufferId);
    GLES30.glDrawElements(GLES30.GL_TRIANGLES, indexCount, GLES30.GL_UNSIGNED_SHORT, 0);
    GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, 0);

    if (blendMode != null) {
      GLES30.glDisable(GLES20.GL_BLEND);
      GLES30.glDepthMask(true);
    }

    // Disable vertex arrays
    GLES30.glDisableVertexAttribArray(positionAttribute);
    GLES30.glDisableVertexAttribArray(normalAttribute);
    GLES30.glDisableVertexAttribArray(texCoordAttribute);

    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0);

    ShaderUtil.checkGLError(TAG, "After draw");
  }

  private static void normalizeVec3(float[] v) {
    float reciprocalLength = 1.0f / (float) Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
    v[0] *= reciprocalLength;
    v[1] *= reciprocalLength;
    v[2] *= reciprocalLength;
  }

  public void setUvtransformMatrix(float[] transform) {
    uvTransform = transform;
  }

  public void setDepthTexture(int textureId, int width, int height) {
    depthTextureId = textureId;
    depthAspectRatio = (float) width / height;
  }
}
