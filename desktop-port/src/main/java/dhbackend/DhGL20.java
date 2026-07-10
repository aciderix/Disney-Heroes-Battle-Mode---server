package dhbackend;

import java.nio.*;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

/**
 * Backend GL20 desktop : implémente {@code com.badlogic.gdx.graphics.GL20} du jeu en
 * déléguant aux bindings OpenGL de LWJGL3. Porté depuis le `DsGL20` de DragonSoul — les
 * NOMS de méthodes libGDX GL20 n'étaient pas obfusqués, seul le nom de l'interface l'était
 * (`com.badlogic.gdx.graphics.f`) → ici on cible le nom clair `GL20`. Corps identiques.
 * Surface = les 75 méthodes de l'interface (celles que le jeu référence). Fidélité : RÉEL.
 */
public final class DhGL20 implements com.badlogic.gdx.graphics.GL20 {

    private static float[] sub(float[] a, int off, int len) {
        if (off == 0 && len == a.length) return a;
        float[] r = new float[len];
        System.arraycopy(a, off, r, 0, len);
        return r;
    }

    public void glActiveTexture(int texture) { GL13.glActiveTexture(texture); }
    public void glAttachShader(int program, int shader) { GL20.glAttachShader(program, shader); }
    public void glBindAttribLocation(int program, int index, String name) { GL20.glBindAttribLocation(program, index, name); }
    public void glBindBuffer(int target, int buffer) { GL15.glBindBuffer(target, buffer); }
    public void glBindFramebuffer(int target, int fb) { GL30.glBindFramebuffer(target, fb); }
    public void glBindRenderbuffer(int target, int rb) { GL30.glBindRenderbuffer(target, rb); }
    public void glBindTexture(int target, int texture) { GL11.glBindTexture(target, texture); }
    public void glBlendColor(float r, float g, float b, float a) { GL14.glBlendColor(r, g, b, a); }
    public void glBlendEquation(int mode) { GL14.glBlendEquation(mode); }
    public void glBlendEquationSeparate(int modeRGB, int modeAlpha) { GL20.glBlendEquationSeparate(modeRGB, modeAlpha); }
    public void glBlendFunc(int sfactor, int dfactor) { GL11.glBlendFunc(sfactor, dfactor); }
    public void glBlendFuncSeparate(int srcRGB, int dstRGB, int srcAlpha, int dstAlpha) { GL14.glBlendFuncSeparate(srcRGB, dstRGB, srcAlpha, dstAlpha); }

    public void glBufferData(int target, int size, Buffer data, int usage) {
        if (data == null) GL15.glBufferData(target, size, usage);
        else if (data instanceof ByteBuffer) GL15.glBufferData(target, (ByteBuffer) data, usage);
        else if (data instanceof IntBuffer) GL15.glBufferData(target, (IntBuffer) data, usage);
        else if (data instanceof FloatBuffer) GL15.glBufferData(target, (FloatBuffer) data, usage);
        else if (data instanceof ShortBuffer) GL15.glBufferData(target, (ShortBuffer) data, usage);
        else throw new IllegalArgumentException("buffer type");
    }

    public void glBufferSubData(int target, int offset, int size, Buffer data) {
        if (data instanceof ByteBuffer) GL15.glBufferSubData(target, offset, (ByteBuffer) data);
        else if (data instanceof IntBuffer) GL15.glBufferSubData(target, offset, (IntBuffer) data);
        else if (data instanceof FloatBuffer) GL15.glBufferSubData(target, offset, (FloatBuffer) data);
        else if (data instanceof ShortBuffer) GL15.glBufferSubData(target, offset, (ShortBuffer) data);
        else throw new IllegalArgumentException("buffer type");
    }

    public int glCheckFramebufferStatus(int target) { return GL30.glCheckFramebufferStatus(target); }
    public void glClear(int mask) { GL11.glClear(mask); }
    public void glClearColor(float r, float g, float b, float a) { GL11.glClearColor(r, g, b, a); }
    public void glClearDepthf(float depth) { GL11.glClearDepth(depth); }
    public void glClearStencil(int s) { GL11.glClearStencil(s); }
    public void glColorMask(boolean r, boolean g, boolean b, boolean a) { GL11.glColorMask(r, g, b, a); }
    public void glCompileShader(int shader) { GL20.glCompileShader(shader); }

    public void glCompressedTexImage2D(int target, int level, int internalformat, int width, int height,
                                       int border, int imageSize, Buffer data) {
        GL13.glCompressedTexImage2D(target, level, internalformat, width, height, border, (ByteBuffer) data);
    }
    public void glCompressedTexSubImage2D(int target, int level, int xoffset, int yoffset, int width, int height,
                                          int format, int imageSize, Buffer data) {
        GL13.glCompressedTexSubImage2D(target, level, xoffset, yoffset, width, height, format, (ByteBuffer) data);
    }
    public void glCopyTexImage2D(int target, int level, int internalformat, int x, int y, int width, int height, int border) {
        GL11.glCopyTexImage2D(target, level, internalformat, x, y, width, height, border);
    }
    public void glCopyTexSubImage2D(int target, int level, int xoffset, int yoffset, int x, int y, int width, int height) {
        GL11.glCopyTexSubImage2D(target, level, xoffset, yoffset, x, y, width, height);
    }
    public int glCreateProgram() { return GL20.glCreateProgram(); }
    public int glCreateShader(int type) { return GL20.glCreateShader(type); }
    public void glCullFace(int mode) { GL11.glCullFace(mode); }
    public void glDeleteBuffer(int buffer) { GL15.glDeleteBuffers(buffer); }
    public void glDeleteFramebuffer(int fb) { GL30.glDeleteFramebuffers(fb); }
    public void glDeleteProgram(int program) { GL20.glDeleteProgram(program); }
    public void glDeleteRenderbuffer(int rb) { GL30.glDeleteRenderbuffers(rb); }
    public void glDeleteShader(int shader) { GL20.glDeleteShader(shader); }
    public void glDeleteTexture(int texture) { GL11.glDeleteTextures(texture); }
    public void glDepthFunc(int func) { GL11.glDepthFunc(func); }
    public void glDepthMask(boolean flag) { GL11.glDepthMask(flag); }
    public void glDepthRangef(float zNear, float zFar) { GL11.glDepthRange(zNear, zFar); }
    public void glDisable(int cap) { GL11.glDisable(cap); }
    public void glDisableVertexAttribArray(int index) { GL20.glDisableVertexAttribArray(index); }
    public void glDrawArrays(int mode, int first, int count) { GL11.glDrawArrays(mode, first, count); }
    public void glDrawElements(int mode, int count, int type, int indices) { GL11.glDrawElements(mode, count, type, indices); }
    public void glDrawElements(int mode, int count, int type, Buffer indices) {
        if (indices instanceof ByteBuffer) GL11.glDrawElements(mode, (ByteBuffer) indices);
        else if (indices instanceof ShortBuffer) GL11.glDrawElements(mode, (ShortBuffer) indices);
        else if (indices instanceof IntBuffer) GL11.glDrawElements(mode, (IntBuffer) indices);
        else throw new IllegalArgumentException("index buffer type");
    }
    public void glEnable(int cap) { GL11.glEnable(cap); }
    public void glEnableVertexAttribArray(int index) { GL20.glEnableVertexAttribArray(index); }
    public void glFinish() { GL11.glFinish(); }
    public void glFlush() { GL11.glFlush(); }
    public void glFramebufferRenderbuffer(int target, int attachment, int rbTarget, int rb) { GL30.glFramebufferRenderbuffer(target, attachment, rbTarget, rb); }
    public void glFramebufferTexture2D(int target, int attachment, int texTarget, int texture, int level) { GL30.glFramebufferTexture2D(target, attachment, texTarget, texture, level); }
    public void glFrontFace(int mode) { GL11.glFrontFace(mode); }
    public int glGenBuffer() { return GL15.glGenBuffers(); }
    public int glGenFramebuffer() { return GL30.glGenFramebuffers(); }
    public int glGenRenderbuffer() { return GL30.glGenRenderbuffers(); }
    public int glGenTexture() { return GL11.glGenTextures(); }
    public void glGenerateMipmap(int target) { GL30.glGenerateMipmap(target); }

    public String glGetActiveAttrib(int program, int index, IntBuffer size, Buffer type) { return GL20.glGetActiveAttrib(program, index, 256, size, (IntBuffer) type); }
    public String glGetActiveUniform(int program, int index, IntBuffer size, Buffer type) { return GL20.glGetActiveUniform(program, index, 256, size, (IntBuffer) type); }
    public int glGetAttribLocation(int program, String name) { return GL20.glGetAttribLocation(program, name); }
    public boolean glGetBoolean(int pname) { return GL11.glGetBoolean(pname); }
    public void glGetBooleanv(int pname, Buffer params) { GL11.glGetBooleanv(pname, (ByteBuffer) params); }
    public int glGetError() { return GL11.glGetError(); }
    public void glGetFloatv(int pname, FloatBuffer params) { GL11.glGetFloatv(pname, params); }
    public void glGetIntegerv(int pname, IntBuffer params) { GL11.glGetIntegerv(pname, params); }
    public String glGetProgramInfoLog(int program) { return GL20.glGetProgramInfoLog(program); }
    public void glGetProgramiv(int program, int pname, IntBuffer params) { GL20.glGetProgramiv(program, pname, params); }
    public String glGetShaderInfoLog(int shader) { return GL20.glGetShaderInfoLog(shader); }
    public void glGetShaderiv(int shader, int pname, IntBuffer params) { GL20.glGetShaderiv(shader, pname, params); }
    public String glGetString(int name) { return GL11.glGetString(name); }
    public int glGetUniformLocation(int program, String name) { return GL20.glGetUniformLocation(program, name); }
    public void glHint(int target, int mode) { GL11.glHint(target, mode); }
    public boolean glIsEnabled(int cap) { return GL11.glIsEnabled(cap); }
    public void glLineWidth(float width) { GL11.glLineWidth(width); }
    public void glLinkProgram(int program) { GL20.glLinkProgram(program); }
    public void glPixelStorei(int pname, int param) { GL11.glPixelStorei(pname, param); }
    public void glPolygonOffset(float factor, float units) { GL11.glPolygonOffset(factor, units); }
    public void glReadPixels(int x, int y, int w, int h, int format, int type, Buffer pixels) {
        if (pixels instanceof ByteBuffer) GL11.glReadPixels(x, y, w, h, format, type, (ByteBuffer) pixels);
        else if (pixels instanceof IntBuffer) GL11.glReadPixels(x, y, w, h, format, type, (IntBuffer) pixels);
        else throw new IllegalArgumentException("pixel buffer type");
    }
    public void glRenderbufferStorage(int target, int fmt, int w, int h) { GL30.glRenderbufferStorage(target, fmt, w, h); }
    public void glSampleCoverage(float value, boolean invert) { GL13.glSampleCoverage(value, invert); }
    public void glScissor(int x, int y, int w, int h) { GL11.glScissor(x, y, w, h); }
    public void glShaderSource(int shader, String source) { GL20.glShaderSource(shader, source); }
    public void glStencilFunc(int func, int ref, int mask) { GL11.glStencilFunc(func, ref, mask); }
    public void glStencilFuncSeparate(int face, int func, int ref, int mask) { GL20.glStencilFuncSeparate(face, func, ref, mask); }
    public void glStencilMask(int mask) { GL11.glStencilMask(mask); }
    public void glStencilMaskSeparate(int face, int mask) { GL20.glStencilMaskSeparate(face, mask); }
    public void glStencilOp(int fail, int zfail, int zpass) { GL11.glStencilOp(fail, zfail, zpass); }
    public void glStencilOpSeparate(int face, int fail, int zfail, int zpass) { GL20.glStencilOpSeparate(face, fail, zfail, zpass); }

    public void glTexImage2D(int target, int level, int internalformat, int width, int height,
                             int border, int format, int type, Buffer pixels) {
        if (pixels == null) GL11.glTexImage2D(target, level, internalformat, width, height, border, format, type, (ByteBuffer) null);
        else if (pixels instanceof ByteBuffer) GL11.glTexImage2D(target, level, internalformat, width, height, border, format, type, (ByteBuffer) pixels);
        else if (pixels instanceof ShortBuffer) GL11.glTexImage2D(target, level, internalformat, width, height, border, format, type, (ShortBuffer) pixels);
        else if (pixels instanceof IntBuffer) GL11.glTexImage2D(target, level, internalformat, width, height, border, format, type, (IntBuffer) pixels);
        else if (pixels instanceof FloatBuffer) GL11.glTexImage2D(target, level, internalformat, width, height, border, format, type, (FloatBuffer) pixels);
        else throw new IllegalArgumentException("pixel buffer type");
    }

    public void glTexParameterf(int target, int pname, float param) { GL11.glTexParameterf(target, pname, param); }
    public void glTexParameterfv(int target, int pname, FloatBuffer params) { GL11.glTexParameterfv(target, pname, params); }
    public void glTexParameteri(int target, int pname, int param) { GL11.glTexParameteri(target, pname, param); }
    public void glTexParameteriv(int target, int pname, IntBuffer params) { GL11.glTexParameteriv(target, pname, params); }
    public void glTexSubImage2D(int target, int level, int xoffset, int yoffset, int width, int height,
                                int format, int type, Buffer pixels) {
        if (pixels instanceof ByteBuffer) GL11.glTexSubImage2D(target, level, xoffset, yoffset, width, height, format, type, (ByteBuffer) pixels);
        else if (pixels instanceof ShortBuffer) GL11.glTexSubImage2D(target, level, xoffset, yoffset, width, height, format, type, (ShortBuffer) pixels);
        else if (pixels instanceof IntBuffer) GL11.glTexSubImage2D(target, level, xoffset, yoffset, width, height, format, type, (IntBuffer) pixels);
        else throw new IllegalArgumentException("pixel buffer type");
    }

    public void glUniform1f(int location, float x) { GL20.glUniform1f(location, x); }
    public void glUniform1fv(int location, int count, FloatBuffer v) { GL20.glUniform1fv(location, v); }
    public void glUniform1fv(int location, int count, float[] v, int offset) { GL20.glUniform1fv(location, sub(v, offset, count)); }
    public void glUniform1i(int location, int x) { GL20.glUniform1i(location, x); }
    public void glUniform1iv(int location, int count, IntBuffer v) { GL20.glUniform1iv(location, v); }
    public void glUniform1iv(int location, int count, int[] v, int offset) { GL20.glUniform1iv(location, v); }
    public void glUniform2f(int location, float x, float y) { GL20.glUniform2f(location, x, y); }
    public void glUniform2fv(int location, int count, FloatBuffer v) { GL20.glUniform2fv(location, v); }
    public void glUniform2fv(int location, int count, float[] v, int offset) { GL20.glUniform2fv(location, sub(v, offset, count * 2)); }
    public void glUniform2i(int location, int x, int y) { GL20.glUniform2i(location, x, y); }
    public void glUniform2iv(int location, int count, IntBuffer v) { GL20.glUniform2iv(location, v); }
    public void glUniform2iv(int location, int count, int[] v, int offset) { GL20.glUniform2iv(location, v); }
    public void glUniform3f(int location, float x, float y, float z) { GL20.glUniform3f(location, x, y, z); }
    public void glUniform3fv(int location, int count, FloatBuffer v) { GL20.glUniform3fv(location, v); }
    public void glUniform3fv(int location, int count, float[] v, int offset) { GL20.glUniform3fv(location, sub(v, offset, count * 3)); }
    public void glUniform3i(int location, int x, int y, int z) { GL20.glUniform3i(location, x, y, z); }
    public void glUniform3iv(int location, int count, IntBuffer v) { GL20.glUniform3iv(location, v); }
    public void glUniform3iv(int location, int count, int[] v, int offset) { GL20.glUniform3iv(location, v); }
    public void glUniform4f(int location, float x, float y, float z, float w) { GL20.glUniform4f(location, x, y, z, w); }
    public void glUniform4fv(int location, int count, FloatBuffer v) { GL20.glUniform4fv(location, v); }
    public void glUniform4fv(int location, int count, float[] v, int offset) { GL20.glUniform4fv(location, sub(v, offset, count * 4)); }
    public void glUniform4i(int location, int x, int y, int z, int w) { GL20.glUniform4i(location, x, y, z, w); }
    public void glUniform4iv(int location, int count, IntBuffer v) { GL20.glUniform4iv(location, v); }
    public void glUniform4iv(int location, int count, int[] v, int offset) { GL20.glUniform4iv(location, v); }
    public void glUniformMatrix2fv(int location, int count, boolean transpose, FloatBuffer value) { GL20.glUniformMatrix2fv(location, transpose, value); }
    public void glUniformMatrix2fv(int location, int count, boolean transpose, float[] value, int offset) { GL20.glUniformMatrix2fv(location, transpose, sub(value, offset, count * 4)); }
    public void glUniformMatrix3fv(int location, int count, boolean transpose, FloatBuffer value) { GL20.glUniformMatrix3fv(location, transpose, value); }
    public void glUniformMatrix3fv(int location, int count, boolean transpose, float[] value, int offset) { GL20.glUniformMatrix3fv(location, transpose, sub(value, offset, count * 9)); }
    public void glUniformMatrix4fv(int location, int count, boolean transpose, FloatBuffer value) { GL20.glUniformMatrix4fv(location, transpose, value); }
    public void glUniformMatrix4fv(int location, int count, boolean transpose, float[] value, int offset) { GL20.glUniformMatrix4fv(location, transpose, sub(value, offset, count * 16)); }
    public void glUseProgram(int program) { GL20.glUseProgram(program); }
    public void glValidateProgram(int program) { GL20.glValidateProgram(program); }
    public void glVertexAttrib1f(int index, float x) { GL20.glVertexAttrib1f(index, x); }
    public void glVertexAttrib1fv(int index, FloatBuffer v) { GL20.glVertexAttrib1f(index, v.get(v.position())); }
    public void glVertexAttrib2f(int index, float x, float y) { GL20.glVertexAttrib2f(index, x, y); }
    public void glVertexAttrib2fv(int index, FloatBuffer v) { GL20.glVertexAttrib2f(index, v.get(v.position()), v.get(v.position() + 1)); }
    public void glVertexAttrib3f(int index, float x, float y, float z) { GL20.glVertexAttrib3f(index, x, y, z); }
    public void glVertexAttrib3fv(int index, FloatBuffer v) { GL20.glVertexAttrib3f(index, v.get(v.position()), v.get(v.position() + 1), v.get(v.position() + 2)); }
    public void glVertexAttrib4f(int index, float x, float y, float z, float w) { GL20.glVertexAttrib4f(index, x, y, z, w); }
    public void glVertexAttrib4fv(int index, FloatBuffer v) { GL20.glVertexAttrib4f(index, v.get(v.position()), v.get(v.position() + 1), v.get(v.position() + 2), v.get(v.position() + 3)); }
    public void glVertexAttribPointer(int index, int size, int type, boolean normalized, int stride, int offset) { GL20.glVertexAttribPointer(index, size, type, normalized, stride, offset); }
    public void glVertexAttribPointer(int index, int size, int type, boolean normalized, int stride, Buffer ptr) {
        if (ptr instanceof ByteBuffer) GL20.glVertexAttribPointer(index, size, type, normalized, stride, (ByteBuffer) ptr);
        else if (ptr instanceof FloatBuffer) GL20.glVertexAttribPointer(index, size, type, normalized, stride, (FloatBuffer) ptr);
        else if (ptr instanceof ShortBuffer) GL20.glVertexAttribPointer(index, size, type, normalized, stride, (ShortBuffer) ptr);
        else if (ptr instanceof IntBuffer) GL20.glVertexAttribPointer(index, size, type, normalized, stride, (IntBuffer) ptr);
        else throw new IllegalArgumentException("vertex attrib buffer type");
    }
    public void glViewport(int x, int y, int w, int h) { GL11.glViewport(x, y, w, h); }
}
