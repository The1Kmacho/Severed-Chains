package legend.visualremaster;

import legend.core.renderer.BufferUsage;
import legend.core.renderer.Mesh;
import legend.core.renderer.MeshObj;
import legend.core.renderer.Obj;
import legend.core.renderer.VertexOrder;
import org.joml.Vector2f;
import org.joml.Vector3f;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static legend.core.GameEngine.RENDERER;

/**
 * Minimal OBJ loader for rigid battle-model replacement parts.
 *
 * Supported:
 * - v, vt, vn
 * - triangular or polygonal f records (fan triangulated)
 * - positive and negative OBJ indices
 *
 * Material assignment stays deliberately simple for the first remaster slice:
 * one shared PNG atlas is bound by the Visual Remaster mod.
 */
final class ModernObjLoader {
  private static final int VERTEX_SIZE = 16;
  private static final float BPP_24_TPAGE = 3 << 7;
  private static final float FLAGS_LIT_TEXTURED = 0x1 | 0x2;

  private ModernObjLoader() { }

  static Obj load(final String name, final Path path) throws IOException {
    final List<Vector3f> positions = new ArrayList<>();
    final List<Vector2f> uvs = new ArrayList<>();
    final List<Vector3f> normals = new ArrayList<>();
    final List<FaceVertex[]> triangles = new ArrayList<>();

    for(final String rawLine : Files.readAllLines(path)) {
      final String line = rawLine.trim();
      if(line.isEmpty() || line.startsWith("#")) {
        continue;
      }

      final String[] tokens = line.split("\\s+");
      switch(tokens[0]) {
        case "v" -> positions.add(new Vector3f(
          Float.parseFloat(tokens[1]),
          Float.parseFloat(tokens[2]),
          Float.parseFloat(tokens[3])
        ));
        case "vt" -> uvs.add(new Vector2f(
          Float.parseFloat(tokens[1]),
          Float.parseFloat(tokens[2])
        ));
        case "vn" -> normals.add(new Vector3f(
          Float.parseFloat(tokens[1]),
          Float.parseFloat(tokens[2]),
          Float.parseFloat(tokens[3])
        ).normalize());
        case "f" -> {
          if(tokens.length < 4) {
            continue;
          }

          final FaceVertex root = parseFaceVertex(tokens[1], positions.size(), uvs.size(), normals.size());
          for(int i = 2; i < tokens.length - 1; i++) {
            triangles.add(new FaceVertex[] {
              root,
              parseFaceVertex(tokens[i], positions.size(), uvs.size(), normals.size()),
              parseFaceVertex(tokens[i + 1], positions.size(), uvs.size(), normals.size())
            });
          }
        }
      }
    }

    if(triangles.isEmpty()) {
      throw new IOException("OBJ contains no faces: " + path);
    }

    final float[] vertices = new float[triangles.size() * 3 * VERTEX_SIZE];
    final int[] indices = new int[triangles.size() * 6];

    int vertexOffset = 0;
    int indexOffset = 0;
    int vertexIndex = 0;

    for(final FaceVertex[] triangle : triangles) {
      final Vector3f fallbackNormal = computeFaceNormal(
        positions.get(triangle[0].position),
        positions.get(triangle[1].position),
        positions.get(triangle[2].position)
      );

      for(final FaceVertex fv : triangle) {
        final Vector3f position = positions.get(fv.position);
        final Vector2f uv = fv.uv >= 0 ? uvs.get(fv.uv) : new Vector2f();
        final Vector3f normal = fv.normal >= 0 ? normals.get(fv.normal) : fallbackNormal;

        vertices[vertexOffset++] = position.x;
        vertices[vertexOffset++] = position.y;
        vertices[vertexOffset++] = position.z;
        vertices[vertexOffset++] = vertexIndex;

        vertices[vertexOffset++] = normal.x;
        vertices[vertexOffset++] = normal.y;
        vertices[vertexOffset++] = normal.z;

        vertices[vertexOffset++] = uv.x;
        vertices[vertexOffset++] = 1.0f - uv.y;

        // Tell the existing TMD battle shader this is a normalized 24-bit texture.
        vertices[vertexOffset++] = BPP_24_TPAGE;
        vertices[vertexOffset++] = 0.0f;

        vertices[vertexOffset++] = 1.0f;
        vertices[vertexOffset++] = 1.0f;
        vertices[vertexOffset++] = 1.0f;
        vertices[vertexOffset++] = 1.0f;

        vertices[vertexOffset++] = FLAGS_LIT_TEXTURED;
        vertexIndex++;
      }

      final int base = vertexIndex - 3;
      // The existing battle geometry shader expects TRIANGLES_ADJACENCY and reads
      // real triangle vertices from slots 0, 2, and 4. Adjacency vertices are not
      // otherwise used for non-quad remaster geometry, so duplicate each endpoint.
      indices[indexOffset++] = base;
      indices[indexOffset++] = base;
      indices[indexOffset++] = base + 1;
      indices[indexOffset++] = base + 1;
      indices[indexOffset++] = base + 2;
      indices[indexOffset++] = base + 2;
    }

    final Mesh mesh = RENDERER.api().makeMesh(
      name,
      VertexOrder.TRIANGLES_ADJACENCY,
      vertices,
      indices,
      true,
      false,
      null,
      BufferUsage.STATIC
    );

    mesh.attribute(0, 0L, 4, VERTEX_SIZE);
    mesh.attribute(1, 4L, 3, VERTEX_SIZE);
    mesh.attribute(2, 7L, 2, VERTEX_SIZE);
    mesh.attribute(3, 9L, 1, VERTEX_SIZE);
    mesh.attribute(4, 10L, 1, VERTEX_SIZE);
    mesh.attribute(5, 11L, 4, VERTEX_SIZE);
    mesh.attribute(6, 15L, 1, VERTEX_SIZE);

    return new MeshObj(name, new Mesh[] {mesh});
  }

  private static FaceVertex parseFaceVertex(
    final String token,
    final int positionCount,
    final int uvCount,
    final int normalCount
  ) {
    final String[] parts = token.split("/", -1);
    final int position = resolveIndex(parts[0], positionCount);
    final int uv = parts.length > 1 && !parts[1].isEmpty() ? resolveIndex(parts[1], uvCount) : -1;
    final int normal = parts.length > 2 && !parts[2].isEmpty() ? resolveIndex(parts[2], normalCount) : -1;
    return new FaceVertex(position, uv, normal);
  }

  private static int resolveIndex(final String index, final int size) {
    final int value = Integer.parseInt(index);
    return value > 0 ? value - 1 : size + value;
  }

  private static Vector3f computeFaceNormal(
    final Vector3f a,
    final Vector3f b,
    final Vector3f c
  ) {
    final Vector3f ab = new Vector3f(b).sub(a);
    final Vector3f ac = new Vector3f(c).sub(a);
    return ab.cross(ac).normalize();
  }

  private record FaceVertex(int position, int uv, int normal) { }
}
