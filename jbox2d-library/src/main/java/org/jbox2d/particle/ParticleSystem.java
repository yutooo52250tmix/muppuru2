package org.jbox2d.particle;

import java.lang.reflect.Array;
import java.util.Arrays;

import org.jbox2d.callbacks.DestructionListener;
import org.jbox2d.callbacks.QueryCallback;
import org.jbox2d.callbacks.RayCastCallback;
import org.jbox2d.collision.AABB;
import org.jbox2d.collision.shapes.Shape;
import org.jbox2d.common.BufferUtils;
import org.jbox2d.common.MathUtils;
import org.jbox2d.common.Rot;
import org.jbox2d.common.Settings;
import org.jbox2d.common.Transform;
import org.jbox2d.common.Vec2;
import org.jbox2d.dynamics.Body;
import org.jbox2d.dynamics.Fixture;
import org.jbox2d.dynamics.TimeStep;
import org.jbox2d.dynamics.World;
import org.jbox2d.particle.VoronoiDiagram.VoronoiDiagramCallback;

public class ParticleSystem {
  /** All particle types that require creating pairs */
  private static final int k_pairFlags = ParticleType.b2_springParticle;
  /** All particle types that require creating triads */
  private static final int k_triadFlags = ParticleType.b2_elasticParticle;
  /** All particle types that require computing depth */
  private static final int k_noPressureFlags = ParticleType.b2_powderParticle;

  static final int xTruncBits = 12;
  static final int yTruncBits = 12;
  static final int tagBits = 8 * 4 /* sizeof(int) */;
  static final int yOffset = 1 << (yTruncBits - 1);
  static final int yShift = tagBits - yTruncBits;
  static final int xShift = tagBits - yTruncBits - xTruncBits;
  static final int xScale = 1 << xShift;
  static final int xOffset = xScale * (1 << (xTruncBits - 1));
  static final int xMask = (1 << xTruncBits) - 1;
  static final int yMask = (1 << yTruncBits) - 1;

  static int computeTag(float x, float y) {
    return ((int) (y + yOffset) << yShift) + (int) (xScale * x + xOffset);
  }

  static int computeRelativeTag(int tag, int x, int y) {
    return tag + (y << yShift) + (x << xShift);
  }

  static int limitCapacity(int capacity, int maxCount) {
    return maxCount != 0 && capacity > maxCount ? maxCount : capacity;
  }

  int m_timestamp;
  int m_allParticleFlags;
  int m_allGroupFlags;
  float m_density;
  float m_inverseDensity;
  float m_gravityScale;
  float m_particleDiameter;
  float m_inverseDiameter;
  float m_squaredDiameter;

  int m_count;
  int m_internalAllocatedCapacity;
  int m_maxCount;
  ParticleBufferInt m_flagsBuffer;
  ParticleBuffer<Vec2> m_positionBuffer;
  ParticleBuffer<Vec2> m_velocityBuffer;
  float[] m_accumulationBuffer; // temporary values
  Vec2[] m_accumulation2Buffer; // temporary vector values
  float[] m_depthBuffer; // distance from the surface

  ParticleBuffer<ParticleColor> m_colorBuffer;
  ParticleGroup[] m_groupBuffer;
  ParticleBuffer<Object> m_userDataBuffer;

  int m_proxyCount;
  int m_proxyCapacity;
  Proxy[] m_proxyBuffer;

  int m_contactCount;
  int m_contactCapacity;
  ParticleContact[] m_contactBuffer;

  int m_bodyContactCount;
  int m_bodyContactCapacity;
  ParticleBodyContact[] m_bodyContactBuffer;

  int m_pairCount;
  int m_pairCapacity;
  Pair[] m_pairBuffer;

  int m_triadCount;
  int m_triadCapacity;
  Triad[] m_triadBuffer;

  int m_groupCount;
  ParticleGroup m_groupList;

  float m_pressureStrength;
  float m_dampingStrength;
  float m_elasticStrength;
  float m_springStrength;
  float m_viscousStrength;
  float m_surfaceTensionStrengthA;
  float m_surfaceTensionStrengthB;
  float m_powderStrength;
  float m_ejectionStrength;
  float m_colorMixingStrength;

  World m_world;

  public ParticleSystem() {
    m_timestamp = 0;
    m_allParticleFlags = 0;
    m_allGroupFlags = 0;
    m_density = 1;
    m_inverseDensity = 1;
    m_gravityScale = 1;
    m_particleDiameter = 1;
    m_inverseDiameter = 1;
    m_squaredDiameter = 1;

    m_count = 0;
    m_internalAllocatedCapacity = 0;
    m_maxCount = 0;

    m_proxyCount = 0;
    m_proxyCapacity = 0;

    m_contactCount = 0;
    m_contactCapacity = 0;

    m_bodyContactCount = 0;
    m_bodyContactCapacity = 0;

    m_pairCount = 0;
    m_pairCapacity = 0;

    m_triadCount = 0;
    m_triadCapacity = 0;

    m_groupCount = 0;

    m_pressureStrength = 0.05f;
    m_dampingStrength = 1.0f;
    m_elasticStrength = 0.25f;
    m_springStrength = 0.25f;
    m_viscousStrength = 0.25f;
    m_surfaceTensionStrengthA = 0.1f;
    m_surfaceTensionStrengthB = 0.2f;
    m_powderStrength = 0.5f;
    m_ejectionStrength = 0.5f;
    m_colorMixingStrength = 0.5f;

    m_positionBuffer = new ParticleBuffer<Vec2>(Vec2.class);
    m_velocityBuffer = new ParticleBuffer<Vec2>(Vec2.class);
    m_colorBuffer = new ParticleBuffer<ParticleColor>(ParticleColor.class);
    m_userDataBuffer = new ParticleBuffer<Object>(Object.class);
  }

  int createParticle(ParticleDef def) {
    if (m_count >= m_internalAllocatedCapacity) {
      int capacity = m_count != 0 ? 2 * m_count : Settings.minParticleBufferCapacity;
      capacity = limitCapacity(capacity, m_maxCount);
      capacity = limitCapacity(capacity, m_flagsBuffer.userSuppliedCapacity);
      capacity = limitCapacity(capacity, m_positionBuffer.userSuppliedCapacity);
      capacity = limitCapacity(capacity, m_velocityBuffer.userSuppliedCapacity);
      capacity = limitCapacity(capacity, m_colorBuffer.userSuppliedCapacity);
      capacity = limitCapacity(capacity, m_userDataBuffer.userSuppliedCapacity);
      if (m_internalAllocatedCapacity < capacity) {
        m_flagsBuffer.data =
            reallocateBuffer(m_flagsBuffer, m_internalAllocatedCapacity, capacity, false);
        m_positionBuffer.data =
            reallocateBuffer(m_positionBuffer, m_internalAllocatedCapacity, capacity, false);
        m_velocityBuffer.data =
            reallocateBuffer(m_velocityBuffer, m_internalAllocatedCapacity, capacity, false);
        m_accumulationBuffer =
            BufferUtils.reallocateBuffer(m_accumulationBuffer, 0, m_internalAllocatedCapacity,
                capacity, false);
        m_accumulation2Buffer =
            BufferUtils.reallocateBuffer(Vec2.class, m_accumulation2Buffer, 0,
                m_internalAllocatedCapacity, capacity, true);
        m_depthBuffer =
            BufferUtils.reallocateBuffer(m_depthBuffer, 0, m_internalAllocatedCapacity, capacity,
                true);
        m_colorBuffer.data =
            reallocateBuffer(m_colorBuffer, m_internalAllocatedCapacity, capacity, true);
        m_groupBuffer =
            BufferUtils.reallocateBuffer(ParticleGroup.class, m_groupBuffer, 0,
                m_internalAllocatedCapacity, capacity, false);
        m_userDataBuffer.data =
            reallocateBuffer(m_userDataBuffer, m_internalAllocatedCapacity, capacity, true);
        m_internalAllocatedCapacity = capacity;
      }
    }
    if (m_count >= m_internalAllocatedCapacity) {
      return Settings.invalidParticleIndex;
    }
    int index = m_count++;
    m_flagsBuffer.data[index] = def.flags;
    m_positionBuffer.data[index] = def.position;
    m_velocityBuffer.data[index] = def.velocity;
    m_groupBuffer[index] = null;
    if (m_depthBuffer != null) {
      m_depthBuffer[index] = 0;
    }
    if (m_colorBuffer.data != null || !def.color.isZero()) {
      m_colorBuffer.data = requestParticleBuffer(m_colorBuffer.dataClass, m_colorBuffer.data);
      m_colorBuffer.data[index] = def.color;
    }
    if (m_userDataBuffer.data != null || def.userData != null) {
      m_userDataBuffer.data =
          requestParticleBuffer(m_userDataBuffer.dataClass, m_userDataBuffer.data);
      m_userDataBuffer.data[index] = def.userData;
    }
    if (m_proxyCount >= m_proxyCapacity) {
      int oldCapacity = m_proxyCapacity;
      int newCapacity = m_proxyCount != 0 ? 2 * m_proxyCount : Settings.minParticleBufferCapacity;
      m_proxyBuffer =
          BufferUtils.reallocateBuffer(Proxy.class, m_proxyBuffer, oldCapacity, newCapacity);
      m_proxyCapacity = newCapacity;
    }
    m_proxyBuffer[m_proxyCount++].index = index;
    return index;
  }

  void destroyParticle(int index, boolean callDestructionListener) {
    int flags = ParticleType.b2_zombieParticle;
    if (callDestructionListener) {
      flags |= ParticleType.b2_destructionListener;
    }
    m_flagsBuffer.data[index] |= flags;
  }

  private final AABB temp = new AABB();

  int destroyParticlesInShape(Shape shape, Transform xf, boolean callDestructionListener) {
    DestroyParticlesInShapeCallback callback =
        new DestroyParticlesInShapeCallback(this, shape, xf, callDestructionListener);
    shape.computeAABB(temp, xf, 0);
    m_world.queryAABB(callback, temp);
    return callback.destroyed;
  }

  void DestroyParticlesInGroup(ParticleGroup group, boolean callDestructionListener) {
    for (int i = group.m_firstIndex; i < group.m_lastIndex; i++) {
      destroyParticle(i, callDestructionListener);
    }
  }

  private final AABB temp2 = new AABB();
  private final Vec2 tempVec = new Vec2();
  private final Transform tempTransform = new Transform();
  private final Transform tempTransform2 = new Transform();
  private CreateParticleGroupCallback createParticleGroupCallback =
      new CreateParticleGroupCallback();
  private final ParticleDef tempParticleDef = new ParticleDef();

  ParticleGroup createParticleGroup(ParticleGroupDef groupDef) {
    float stride = getParticleStride();
    final Transform identity = tempTransform;
    identity.setIdentity();
    Transform transform = tempTransform2;
    transform.setIdentity();
    int firstIndex = m_count;
    if (groupDef.shape != null) {
      final ParticleDef particleDef = tempParticleDef;
      particleDef.flags = groupDef.flags;
      particleDef.color.set(groupDef.color);
      particleDef.userData = groupDef.userData;
      Shape shape = groupDef.shape;
      transform.set(groupDef.position, groupDef.angle);
      AABB aabb = temp;
      int childCount = shape.getChildCount();
      for (int childIndex = 0; childIndex < childCount; childIndex++) {
        if (childIndex == 0) {
          shape.computeAABB(aabb, identity, childIndex);
        } else {
          AABB childAABB = temp2;
          shape.computeAABB(childAABB, identity, childIndex);
          aabb.combine(childAABB);
        }
      }
      final float upperBoundY = aabb.upperBound.y;
      final float upperBoundX = aabb.upperBound.x;
      for (float y = MathUtils.floor(aabb.lowerBound.y / stride) * stride; y < upperBoundY; y +=
          stride) {
        for (float x = MathUtils.floor(aabb.lowerBound.x / stride) * stride; x < upperBoundX; x +=
            stride) {
          Vec2 p = tempVec;
          p.x = x;
          p.y = y;
          if (shape.testPoint(identity, p)) {
            Transform.mulToOut(transform, p, p);
            particleDef.position.x = p.x;
            particleDef.position.y = p.y;
            p.subLocal(groupDef.position);
            Vec2.crossToOutUnsafe(groupDef.angularVelocity, p, particleDef.velocity);
            particleDef.velocity.addLocal(groupDef.linearVelocity);
            createParticle(particleDef);
          }
        }
      }
    }
    int lastIndex = m_count;

    ParticleGroup group = new ParticleGroup();
    group.m_system = this;
    group.m_firstIndex = firstIndex;
    group.m_lastIndex = lastIndex;
    group.m_groupFlags = groupDef.groupFlags;
    group.m_strength = groupDef.strength;
    group.m_userData = groupDef.userData;
    group.m_transform.set(transform);
    group.m_destroyAutomatically = groupDef.destroyAutomatically;
    group.m_prev = null;
    group.m_next = m_groupList;
    if (m_groupList != null) {
      m_groupList.m_prev = group;
    }
    m_groupList = group;
    ++m_groupCount;
    for (int i = firstIndex; i < lastIndex; i++) {
      m_groupBuffer[i] = group;
    }

    updateContacts(true);
    if ((groupDef.flags & k_pairFlags) != 0) {
      for (int k = 0; k < m_contactCount; k++) {
        ParticleContact contact = m_contactBuffer[k];
        int a = contact.indexA;
        int b = contact.indexB;
        if (a > b) {
          int temp = a;
          a = b;
          b = temp;
        }
        if (firstIndex <= a && b < lastIndex) {
          if (m_pairCount >= m_pairCapacity) {
            int oldCapacity = m_pairCapacity;
            int newCapacity =
                m_pairCount != 0 ? 2 * m_pairCount : Settings.minParticleBufferCapacity;
            m_pairBuffer =
                BufferUtils.reallocateBuffer(Pair.class, m_pairBuffer, oldCapacity, newCapacity);
            m_pairCapacity = newCapacity;
          }
          Pair pair = m_pairBuffer[m_pairCount];
          pair.indexA = a;
          pair.indexB = b;
          pair.flags = contact.flags;
          pair.strength = groupDef.strength;
          pair.distance = MathUtils.distance(m_positionBuffer.data[a], m_positionBuffer.data[b]);
          m_pairCount++;
        }
      }
    }
    if ((groupDef.flags & k_triadFlags) != 0) {
      VoronoiDiagram diagram = new VoronoiDiagram(lastIndex - firstIndex);
      for (int i = firstIndex; i < lastIndex; i++) {
        diagram.addGenerator(m_positionBuffer.data[i], i);
      }
      diagram.generate(stride / 2);
      createParticleGroupCallback.system = this;
      createParticleGroupCallback.def = groupDef;
      createParticleGroupCallback.firstIndex = firstIndex;
      diagram.getNodes(createParticleGroupCallback);
    }
    if ((groupDef.groupFlags & ParticleGroupType.b2_solidParticleGroup) != 0) {
      computeDepthForGroup(group);
    }

    return group;
  }

  public void joinParticleGroups(ParticleGroup groupA, ParticleGroup groupB) {
    assert (groupA != groupB);
    RotateBuffer(groupB.m_firstIndex, groupB.m_lastIndex, m_count);
    assert (groupB.m_lastIndex == m_count);
    RotateBuffer(groupA.m_firstIndex, groupA.m_lastIndex, groupB.m_firstIndex);
    assert (groupA.m_lastIndex == groupB.m_firstIndex);

    int particleFlags = 0;
    for (int i = groupA.m_firstIndex; i < groupB.m_lastIndex; i++) {
      particleFlags |= m_flagsBuffer.data[i];
    }

    updateContacts(true);
    if ((particleFlags & k_pairFlags) != 0) {
      for (int k = 0; k < m_contactCount; k++) {
        final ParticleContact contact = m_contactBuffer[k];
        int a = contact.indexA;
        int b = contact.indexB;
        if (a > b) {
          int temp = a;
          a = b;
          b = temp;
        }
        if (groupA.m_firstIndex <= a && a < groupA.m_lastIndex && groupB.m_firstIndex <= b
            && b < groupB.m_lastIndex) {
          if (m_pairCount >= m_pairCapacity) {
            int oldCapacity = m_pairCapacity;
            int newCapacity =
                m_pairCount != 0 ? 2 * m_pairCount : Settings.minParticleBufferCapacity;
            m_pairBuffer =
                BufferUtils.reallocateBuffer(Pair.class, m_pairBuffer, oldCapacity, newCapacity);
            m_pairCapacity = newCapacity;
          }
          Pair pair = m_pairBuffer[m_pairCount];
          pair.indexA = a;
          pair.indexB = b;
          pair.flags = contact.flags;
          pair.strength = MathUtils.min(groupA.m_strength, groupB.m_strength);
          pair.distance = MathUtils.distance(m_positionBuffer.data[a], m_positionBuffer.data[b]);
          m_pairCount++;
        }
      }
    }
    if ((particleFlags & k_triadFlags) != 0) {
      VoronoiDiagram diagram = new VoronoiDiagram(groupB.m_lastIndex - groupA.m_firstIndex);
      for (int i = groupA.m_firstIndex; i < groupB.m_lastIndex; i++) {
        if ((m_flagsBuffer.data[i] & ParticleType.b2_zombieParticle) == 0) {
          diagram.addGenerator(m_positionBuffer.data[i], i);
        }
      }
      diagram.generate(getParticleStride() / 2);
      JoinParticleGroupsCallback callback = new JoinParticleGroupsCallback();
      callback.system = this;
      callback.groupA = groupA;
      callback.groupB = groupB;
      diagram.getNodes(callback);
    }

    for (int i = groupB.m_firstIndex; i < groupB.m_lastIndex; i++) {
      m_groupBuffer[i] = groupA;
    }
    int groupFlags = groupA.m_groupFlags | groupB.m_groupFlags;
    groupA.m_groupFlags = groupFlags;
    groupA.m_lastIndex = groupB.m_lastIndex;
    groupB.m_firstIndex = groupB.m_lastIndex;
    destroyParticleGroup(groupB);

    if ((groupFlags & ParticleGroupType.b2_solidParticleGroup) != 0) {
      computeDepthForGroup(groupA);
    }
  }


  // Only called from solveZombie() or JoinParticleGroups().
  public void destroyParticleGroup(ParticleGroup group) {
    assert (m_groupCount > 0);
    assert (group != null);

    if (m_world.getDestructionListener() != null) {
      m_world.getDestructionListener().sayGoodbye(group);
    }

    for (int i = group.m_firstIndex; i < group.m_lastIndex; i++) {
      m_groupBuffer[i] = null;
    }

    if (group.m_prev != null) {
      group.m_prev.m_next = group.m_next;
    }
    if (group.m_next != null) {
      group.m_next.m_prev = group.m_prev;
    }
    if (group == m_groupList) {
      m_groupList = group.m_next;
    }

    --m_groupCount;
  }

  public void computeDepthForGroup(ParticleGroup group) {
    for (int i = group.m_firstIndex; i < group.m_lastIndex; i++) {
      m_accumulationBuffer[i] = 0;
    }
    for (int k = 0; k < m_contactCount; k++) {
      final ParticleContact contact = m_contactBuffer[k];
      int a = contact.indexA;
      int b = contact.indexB;
      if (a >= group.m_firstIndex && a < group.m_lastIndex && b >= group.m_firstIndex
          && b < group.m_lastIndex) {
        float w = contact.weight;
        m_accumulationBuffer[a] += w;
        m_accumulationBuffer[b] += w;
      }
    }
    m_depthBuffer = requestParticleBuffer(m_depthBuffer);
    for (int i = group.m_firstIndex; i < group.m_lastIndex; i++) {
      float w = m_accumulationBuffer[i];
      m_depthBuffer[i] = w < 0.8f ? 0 : Float.MAX_VALUE;
    }
    int interationCount = group.getParticleCount();
    for (int t = 0; t < interationCount; t++) {
      boolean updated = false;
      for (int k = 0; k < m_contactCount; k++) {
        final ParticleContact contact = m_contactBuffer[k];
        int a = contact.indexA;
        int b = contact.indexB;
        if (a >= group.m_firstIndex && a < group.m_lastIndex && b >= group.m_firstIndex
            && b < group.m_lastIndex) {
          float r = 1 - contact.weight;
          float ap0 = m_depthBuffer[a];
          float bp0 = m_depthBuffer[b];
          float ap1 = bp0 + r;
          float bp1 = ap0 + r;
          if (ap0 > ap1) {
            m_depthBuffer[a] = ap1;
            updated = true;
          }
          if (bp0 > bp1) {
            m_depthBuffer[b] = bp1;
            updated = true;
          }
        }
      }
      if (!updated) {
        break;
      }
    }
    for (int i = group.m_firstIndex; i < group.m_lastIndex; i++) {
      float p = m_depthBuffer[i];
      if (p < Float.MAX_VALUE) {
        m_depthBuffer[i] *= m_particleDiameter;
      } else {
        m_depthBuffer[i] = 0;
      }
    }
  }

  public void addContact(int a, int b) {
    Vec2 pa = m_positionBuffer.data[a];
    Vec2 pb = m_positionBuffer.data[b];
    float dx = pb.x - pa.x;
    float dy = pb.y - pa.y;
    float d2 = dx * dx + dy * dy;
    if (d2 < m_squaredDiameter) {
      if (m_contactCount >= m_contactCapacity) {
        int oldCapacity = m_contactCapacity;
        int newCapacity =
            m_contactCount != 0 ? 2 * m_contactCount : Settings.minParticleBufferCapacity;
        m_contactBuffer =
            BufferUtils.reallocateBuffer(ParticleContact.class, m_contactBuffer, oldCapacity,
                newCapacity);
        m_contactCapacity = newCapacity;
      }
      float invD = 1 / MathUtils.sqrt(d2);
      ParticleContact contact = m_contactBuffer[m_contactCount];
      contact.indexA = a;
      contact.indexB = b;
      contact.flags = m_flagsBuffer.data[a] | m_flagsBuffer.data[b];
      contact.weight = 1 - d2 * invD * m_inverseDiameter;
      contact.normal.x = invD * dx;
      contact.normal.y = invD * dy;
      m_contactCount++;
    }
  }

  public void updateContacts(boolean exceptZombie) {
    for (Proxy proxy : m_proxyBuffer) {
      int i = proxy.index;
      Vec2 p = m_positionBuffer.data[i];
      proxy.tag = computeTag(m_inverseDiameter * p.x, m_inverseDiameter * p.y);
    }
    Arrays.sort(m_proxyBuffer);
    m_contactCount = 0;
    int c_index = 0;
    final int length = m_proxyBuffer.length;
    for (int i = 0; i < length; i++) {
      Proxy a = m_proxyBuffer[i];
      int rightTag = computeRelativeTag(a.tag, 1, 0);
      for (int j = i + 1; j < length; j++) {
        Proxy b = m_proxyBuffer[j];
        if (rightTag < b.tag) {
          break;
        }
        addContact(a.index, b.index);
      }
      int bottomLeftTag = computeRelativeTag(a.tag, -1, 1);
      for (; c_index < length; c_index++) {
        Proxy c = m_proxyBuffer[c_index];
        if (bottomLeftTag <= c.tag) {
          break;
        }
      }
      int bottomRightTag = computeRelativeTag(a.tag, 1, 1);

      for (int b_index = c_index; b_index < length; b_index++) {
        Proxy b = m_proxyBuffer[b_index];
        if (bottomRightTag < b.tag) {
          break;
        }
        addContact(a.index, b.index);
      }
    }
    if (exceptZombie) {
      int j = 0;
      for (int i = 0; i < m_contactBuffer.length; i++) {
        if ((m_contactBuffer[i].flags & ParticleType.b2_zombieParticle) != 0) {
          ParticleContact temp = m_contactBuffer[j];
          m_contactBuffer[j++] = m_contactBuffer[i];
          m_contactBuffer[i] = temp;
        }
      }
      m_contactCount = j;
    }
  }

  private final UpdateBodyContactsCallback ubccallback = new UpdateBodyContactsCallback();

  public void updateBodyContacts() {
    final AABB aabb = temp;
    aabb.lowerBound.x = Float.MAX_VALUE;
    aabb.lowerBound.y = Float.MAX_VALUE;
    aabb.upperBound.x = -Float.MAX_VALUE;
    aabb.upperBound.y = -Float.MAX_VALUE;
    for (int i = 0; i < m_count; i++) {
      Vec2 p = m_positionBuffer.data[i];
      Vec2.minToOut(aabb.lowerBound, p, aabb.lowerBound);
      Vec2.maxToOut(aabb.upperBound, p, aabb.upperBound);
    }
    aabb.lowerBound.x -= m_particleDiameter;
    aabb.lowerBound.y -= m_particleDiameter;
    aabb.upperBound.x += m_particleDiameter;
    aabb.upperBound.y += m_particleDiameter;
    m_bodyContactCount = 0;

    m_world.queryAABB(ubccallback, aabb);
  }

  private SolveCollisionCallback sccallback = new SolveCollisionCallback();

  public void solveCollision(TimeStep step) {
    final AABB aabb = temp;
    final Vec2 lowerBound = aabb.lowerBound;
    final Vec2 upperBound = aabb.upperBound;
    lowerBound.x = Float.MAX_VALUE;
    lowerBound.y = Float.MAX_VALUE;
    upperBound.x = -Float.MAX_VALUE;
    upperBound.y = -Float.MAX_VALUE;
    for (int i = 0; i < m_count; i++) {
      final Vec2 v = m_velocityBuffer.data[i];
      final Vec2 p1 = m_positionBuffer.data[i];
      final float p1x = p1.x;
      final float p1y = p1.y;
      final float p2x = p1.x + step.dt * v.x;
      final float p2y = p1.y + step.dt * v.y;
      final float bx = p1x < p2x ? p1x : p2x;
      final float by = p1y < p2y ? p1y : p2y;
      lowerBound.x = lowerBound.x < bx ? lowerBound.x : bx;
      lowerBound.y = lowerBound.y < by ? lowerBound.y : by;
      final float b1x = p1x > p2x ? p1x : p2x;
      final float b1y = p1y > p2y ? p1y : p2y;
      upperBound.x = upperBound.x > b1x ? upperBound.x : b1x;
      upperBound.y = upperBound.y > b1y ? upperBound.y : b1y;
    }
    m_world.queryAABB(sccallback, aabb);
  }

  public void solve(TimeStep step) {
    ++m_timestamp;
    if (m_count == 0) {
      return;
    }
    m_allParticleFlags = 0;
    for (int i = 0; i < m_count; i++) {
      m_allParticleFlags |= m_flagsBuffer.data[i];
    }
    if ((m_allParticleFlags & ParticleType.b2_zombieParticle) != 0) {
      solveZombie();
    }
    m_allGroupFlags = 0;
    for (ParticleGroup group = m_groupList; group != null; group = group.getNext()) {
      m_allGroupFlags |= group.m_groupFlags;
    }
    final float gravityx = step.dt * m_gravityScale * m_world.getGravity().x;
    final float gravityy = step.dt * m_gravityScale * m_world.getGravity().y;
    float criticalVelocytySquared = getCriticalVelocitySquared(step);
    for (int i = 0; i < m_count; i++) {
      Vec2 v = m_velocityBuffer.data[i];
      v.x += gravityx;
      v.y += gravityy;
      float v2 = v.x * v.x + v.y * v.y;
      if (v2 > criticalVelocytySquared) {
        float a = MathUtils.sqrt(criticalVelocytySquared / v2);
        v.x *= a;
        v.y *= a;
      }
    }
    solveCollision(step);
    if ((m_allGroupFlags & ParticleGroupType.b2_rigidParticleGroup) != 0) {
      solveRigid(step);
    }
    if ((m_allParticleFlags & ParticleType.b2_wallParticle) != 0) {
      solveWall(step);
    }
    for (int i = 0; i < m_count; i++) {
      Vec2 pos = m_positionBuffer.data[i];
      Vec2 vel = m_velocityBuffer.data[i];
      pos.x += step.dt * vel.x;
      pos.y += step.dt * vel.y;
    }
    updateBodyContacts();
    updateContacts(false);
    if ((m_allParticleFlags & ParticleType.b2_viscousParticle) != 0) {
      solveViscous(step);
    }
    if ((m_allParticleFlags & ParticleType.b2_powderParticle) != 0) {
      solvePowder(step);
    }
    if ((m_allParticleFlags & ParticleType.b2_tensileParticle) != 0) {
      solveTensile(step);
    }
    if ((m_allParticleFlags & ParticleType.b2_elasticParticle) != 0) {
      solveElastic(step);
    }
    if ((m_allParticleFlags & ParticleType.b2_springParticle) != 0) {
      solveSpring(step);
    }
    if ((m_allGroupFlags & ParticleGroupType.b2_solidParticleGroup) != 0) {
      solveSolid(step);
    }
    if ((m_allParticleFlags & ParticleType.b2_colorMixingParticle) != 0) {
      solveColorMixing(step);
    }
    solvePressure(step);
    solveDamping(step);
  }

  void solvePressure(TimeStep step) {
    // calculates the sum of contact-weights for each particle
    // that means dimensionless density
    for (int i = 0; i < m_count; i++) {
      m_accumulationBuffer[i] = 0;
    }
    for (int k = 0; k < m_bodyContactCount; k++) {
      ParticleBodyContact contact = m_bodyContactBuffer[k];
      int a = contact.index;
      float w = contact.weight;
      m_accumulationBuffer[a] += w;
    }
    for (int k = 0; k < m_contactCount; k++) {
      ParticleContact contact = m_contactBuffer[k];
      int a = contact.indexA;
      int b = contact.indexB;
      float w = contact.weight;
      m_accumulationBuffer[a] += w;
      m_accumulationBuffer[b] += w;
    }
    // ignores powder particles
    if ((m_allParticleFlags & k_noPressureFlags) != 0) {
      for (int i = 0; i < m_count; i++) {
        if ((m_flagsBuffer.data[i] & k_noPressureFlags) != 0) {
          m_accumulationBuffer[i] = 0;
        }
      }
    }
    // calculates pressure as a linear function of density
    float pressurePerWeight = m_pressureStrength * getCriticalPressure(step);
    for (int i = 0; i < m_count; i++) {
      float w = m_accumulationBuffer[i];
      float h =
          pressurePerWeight
              * MathUtils.max(0.0f, MathUtils.min(w, Settings.maxParticleWeight)
                  - Settings.minParticleWeight);
      m_accumulationBuffer[i] = h;
    }
    // applies pressure between each particles in contact
    float velocityPerPressure = step.dt / (m_density * m_particleDiameter);
    for (int k = 0; k < m_bodyContactCount; k++) {
      ParticleBodyContact contact = m_bodyContactBuffer[k];
      int a = contact.index;
      Body b = contact.body;
      float w = contact.weight;
      float m = contact.mass;
      Vec2 n = contact.normal;
      Vec2 p = m_positionBuffer.data[a];
      float h = m_accumulationBuffer[a] + pressurePerWeight * w;
      final Vec2 f = tempVec;
      f.x = velocityPerPressure * w * m * h * n.x;
      f.y = velocityPerPressure * w * m * h * n.y;
      final Vec2 velData = m_velocityBuffer.data[a];
      final float particleInvMass = getParticleInvMass();
      velData.x -= particleInvMass * f.x;
      velData.x -= particleInvMass * f.x;
      b.applyLinearImpulse(f, p, true);
    }
    for (int k = 0; k < m_contactCount; k++) {
      ParticleContact contact = m_contactBuffer[k];
      int a = contact.indexA;
      int b = contact.indexB;
      float w = contact.weight;
      Vec2 n = contact.normal;
      float h = m_accumulationBuffer[a] + m_accumulationBuffer[b];
      final float fx = velocityPerPressure * w * h * n.x;
      final float fy = velocityPerPressure * w * h * n.y;
      final Vec2 velDataA = m_velocityBuffer.data[a];
      final Vec2 velDataB = m_velocityBuffer.data[b];
      velDataA.x -= fx;
      velDataA.y -= fy;
      velDataB.x += fx;
      velDataB.y += fy;
    }
  }

  void solveDamping(TimeStep step) {
    // reduces normal velocity of each contact
    float damping = m_dampingStrength;
    for (int k = 0; k < m_bodyContactCount; k++) {
      final ParticleBodyContact contact = m_bodyContactBuffer[k];
      int a = contact.index;
      Body b = contact.body;
      float w = contact.weight;
      float m = contact.mass;
      Vec2 n = contact.normal;
      Vec2 p = m_positionBuffer.data[a];
      Vec2 v = b.getLinearVelocityFromWorldPoint(p) - m_velocityBuffer.data[a];
      float vn = Vec2.dot(v, n);
      if (vn < 0) {
        Vec2 f = damping * w * m * vn * n;
        m_velocityBuffer.data[a] += getParticleInvMass() * f;
        b.ApplyLinearImpulse(-f, p, true);
      }
    }
    for (int k = 0; k < m_contactCount; k++) {
      final ParticleContact contact = m_contactBuffer[k];
      int a = contact.indexA;
      int b = contact.indexB;
      float w = contact.weight;
      Vec2 n = contact.normal;
      Vec2 v = m_velocityBuffer.data[b] - m_velocityBuffer.data[a];
      float vn = Vec2.dot(v, n);
      if (vn < 0) {
        Vec2 f = damping * w * vn * n;
        m_velocityBuffer.data[a] += f;
        m_velocityBuffer.data[b] -= f;
      }
    }
  }

  public void solveWall(TimeStep step) {
    for (int i = 0; i < m_count; i++) {
      if ((m_flagsBuffer.data[i] & ParticleType.b2_wallParticle) != 0) {
        m_velocityBuffer.data[i].setZero();
      }
    }
  }

  private final Vec2 tempVec2 = new Vec2();
  private final Rot tempRot = new Rot();
  private final Transform tempXf = new Transform();
  private final Transform tempXf2 = new Transform();

  void solveRigid(final TimeStep step) {
    for (ParticleGroup group = m_groupList; group != null; group = group.getNext()) {
      if ((group.m_groupFlags & ParticleGroupType.b2_rigidParticleGroup) != 0) {
        group.updateStatistics();
        Vec2 temp = tempVec;
        Vec2 cross = tempVec2;
        Rot rotation = tempRot;
        rotation.set(step.dt * group.m_angularVelocity);
        Rot.mulToOutUnsafe(rotation, group.m_center, cross);
        temp.set(group.m_linearVelocity).mulLocal(step.dt).addLocal(group.m_center).subLocal(cross);
        tempXf.p.set(temp);
        tempXf.q.set(rotation);
        Transform.mulToOut(tempXf, group.m_transform, group.m_transform);
        final Transform velocityTransform = tempXf2;
        velocityTransform.p.x = step.inv_dt * tempXf.p.x;
        velocityTransform.p.y = step.inv_dt * tempXf.p.y;
        velocityTransform.q.s = step.inv_dt * tempXf.q.s;
        velocityTransform.q.c = step.inv_dt * (tempXf.q.c - 1);
        for (int i = group.m_firstIndex; i < group.m_lastIndex; i++) {
          Transform.mulToOutUnsafe(velocityTransform, m_positionBuffer.data[i],
              m_velocityBuffer.data[i]);
        }
      }
    }
  }

  void solveElastic(final TimeStep step) {
    float elasticStrength = step.inv_dt * m_elasticStrength;
    for (int k = 0; k < m_triadCount; k++) {
      final Triad triad = m_triadBuffer[k];
      if ((triad.flags & ParticleType.b2_elasticParticle) != 0) {
        int a = triad.indexA;
        int b = triad.indexB;
        int c = triad.indexC;
        final Vec2 refoa = triad.pa;
        final Vec2 refob = triad.pb;
        final Vec2 refoc = triad.pc;
        final Vec2 refpa = m_positionBuffer.data[a];
        final Vec2 refpb = m_positionBuffer.data[b];
        final Vec2 refpc = m_positionBuffer.data[c];
        Vec2 p = (float) 1 / 3 * (pa + pb + pc);
        b2Rot r;
        r.s = Vec2.cross(oa, pa) + Vec2.cross(ob, pb) + Vec2.cross(oc, pc);
        r.c = Vec2.dot(oa, pa) + Vec2.dot(ob, pb) + Vec2.dot(oc, pc);
        float r2 = r.s * r.s + r.c * r.c;
        float invR = b2InvSqrt(r2);
        r.s *= invR;
        r.c *= invR;
        float strength = elasticStrength * triad.strength;
        m_velocityBuffer.data[a] += strength * (b2Mul(r, oa) - (pa - p));
        m_velocityBuffer.data[b] += strength * (b2Mul(r, ob) - (pb - p));
        m_velocityBuffer.data[c] += strength * (b2Mul(r, oc) - (pc - p));
      }
    }
  }

  void solveSpring(final TimeStep step) {
    float springStrength = step.inv_dt * m_springStrength;
    for (int k = 0; k < m_pairCount; k++) {
      final Pair pair = m_pairBuffer[k];
      if ((pair.flags & ParticleType.b2_springParticle) != 0) {
        int a = pair.indexA;
        int b = pair.indexB;
        Vec2 d = m_positionBuffer.data[b] - m_positionBuffer.data[a];
        float r0 = pair.distance;
        float r1 = d.Length();
        float strength = springStrength * pair.strength;
        Vec2 f = strength * (r0 - r1) / r1 * d;
        m_velocityBuffer.data[a] -= f;
        m_velocityBuffer.data[b] += f;
      }
    }
  }

  void solveTensile(final TimeStep step) {
    m_accumulation2Buffer = RequestParticleBuffer(m_accumulation2Buffer);
    for (int i = 0; i < m_count; i++) {
      m_accumulationBuffer[i] = 0;
      m_accumulation2Buffer[i] = Vec2_zero;
    }
    for (int k = 0; k < m_contactCount; k++) {
      final ParticleContact contact = m_contactBuffer[k];
      if (contact.flags & ParticleType.b2_tensileParticle) {
        int a = contact.indexA;
        int b = contact.indexB;
        float w = contact.weight;
        Vec2 n = contact.normal;
        m_accumulationBuffer[a] += w;
        m_accumulationBuffer[b] += w;
        m_accumulation2Buffer[a] -= (1 - w) * w * n;
        m_accumulation2Buffer[b] += (1 - w) * w * n;
      }
    }
    float strengthA = m_surfaceTensionStrengthA * getCriticalVelocity(step);
    float strengthB = m_surfaceTensionStrengthB * getCriticalVelocity(step);
    for (int k = 0; k < m_contactCount; k++) {
      final ParticleContact contact = m_contactBuffer[k];
      if (contact.flags & ParticleType.b2_tensileParticle) {
        int a = contact.indexA;
        int b = contact.indexB;
        float w = contact.weight;
        Vec2 n = contact.normal;
        float h = m_accumulationBuffer[a] + m_accumulationBuffer[b];
        Vec2 s = m_accumulation2Buffer[b] - m_accumulation2Buffer[a];
        float fn = (strengthA * (h - 2) + strengthB * Vec2.dot(s, n)) * w;
        Vec2 f = fn * n;
        m_velocityBuffer.data[a] -= f;
        m_velocityBuffer.data[b] += f;
      }
    }
  }

  void solveViscous(final TimeStep step) {
    float viscousStrength = m_viscousStrength;
    for (int k = 0; k < m_bodyContactCount; k++) {
      final ParticleBodyContact contact = m_bodyContactBuffer[k];
      int a = contact.index;
      if (m_flagsBuffer.data[a] & ParticleType.b2_viscousParticle) {
        Body b = contact.body;
        float w = contact.weight;
        float m = contact.mass;
        Vec2 p = m_positionBuffer.data[a];
        Vec2 v = b.getLinearVelocityFromWorldPoint(p) - m_velocityBuffer.data[a];
        Vec2 f = viscousStrength * m * w * v;
        m_velocityBuffer.data[a] += getParticleInvMass() * f;
        b.ApplyLinearImpulse(-f, p, true);
      }
    }
    for (int k = 0; k < m_contactCount; k++) {
      final ParticleContact contact = m_contactBuffer[k];
      if (contact.flags & ParticleType.b2_viscousParticle) {
        int a = contact.indexA;
        int b = contact.indexB;
        float w = contact.weight;
        Vec2 v = m_velocityBuffer.data[b] - m_velocityBuffer.data[a];
        Vec2 f = viscousStrength * w * v;
        m_velocityBuffer.data[a] += f;
        m_velocityBuffer.data[b] -= f;
      }
    }
  }

  void solvePowder(final TimeStep step) {
    float powderStrength = m_powderStrength * getCriticalVelocity(step);
    float minWeight = 1.0f - b2_particleStride;
    for (int k = 0; k < m_bodyContactCount; k++) {
      final ParticleBodyContact contact = m_bodyContactBuffer[k];
      int a = contact.index;
      if (m_flagsBuffer.data[a] & ParticleType.b2_powderParticle) {
        float w = contact.weight;
        if (w > minWeight) {
          Body b = contact.body;
          float m = contact.mass;
          Vec2 p = m_positionBuffer.data[a];
          Vec2 n = contact.normal;
          Vec2 f = powderStrength * m * (w - minWeight) * n;
          m_velocityBuffer.data[a] -= getParticleInvMass() * f;
          b.ApplyLinearImpulse(f, p, true);
        }
      }
    }
    for (int k = 0; k < m_contactCount; k++) {
      final ParticleContact contact = m_contactBuffer[k];
      if (contact.flags & ParticleType.b2_powderParticle) {
        float w = contact.weight;
        if (w > minWeight) {
          int a = contact.indexA;
          int b = contact.indexB;
          Vec2 n = contact.normal;
          Vec2 f = powderStrength * (w - minWeight) * n;
          m_velocityBuffer.data[a] -= f;
          m_velocityBuffer.data[b] += f;
        }
      }
    }
  }

  void solveSolid(final TimeStep step) {
    // applies extra repulsive force from solid particle groups
    m_depthBuffer = RequestParticleBuffer(m_depthBuffer);
    float ejectionStrength = step.inv_dt * m_ejectionStrength;
    for (int k = 0; k < m_contactCount; k++) {
      final ParticleContact contact = m_contactBuffer[k];
      int a = contact.indexA;
      int b = contact.indexB;
      if (m_groupBuffer[a] != m_groupBuffer[b]) {
        float w = contact.weight;
        Vec2 n = contact.normal;
        float h = m_depthBuffer[a] + m_depthBuffer[b];
        Vec2 f = ejectionStrength * h * w * n;
        m_velocityBuffer.data[a] -= f;
        m_velocityBuffer.data[b] += f;
      }
    }
  }

  void solveColorMixing(final TimeStep step) {
    // mixes color between contacting particles
    m_colorBuffer.data = requestParticleBuffer(m_colorBuffer.data);
    int colorMixing256 = (int) (256 * m_colorMixingStrength);
    for (int k = 0; k < m_contactCount; k++) {
      final ParticleContact contact = m_contactBuffer[k];
      int a = contact.indexA;
      int b = contact.indexB;
      if (m_flagsBuffer.data[a] & m_flagsBuffer.data[b] & ParticleType.b2_colorMixingParticle) {
        ParticleColor colorA = m_colorBuffer.data[a];
        ParticleColor colorB = m_colorBuffer.data[b];
        int dr = (colorMixing256 * (colorB.r - colorA.r)) >> 8;
        int dg = (colorMixing256 * (colorB.g - colorA.g)) >> 8;
        int db = (colorMixing256 * (colorB.b - colorA.b)) >> 8;
        int da = (colorMixing256 * (colorB.a - colorA.a)) >> 8;
        colorA.r += dr;
        colorA.g += dg;
        colorA.b += db;
        colorA.a += da;
        colorB.r -= dr;
        colorB.g -= dg;
        colorB.b -= db;
        colorB.a -= da;
      }
    }
  }

  void solveZombie()
  {
      // removes particles with zombie flag
      int newCount = 0;
      int[] newIndices = new int[m_count];
      for (int i = 0; i < m_count; i++)
      {
          int flags = m_flagsBuffer.data[i];
          if ((flags & ParticleType.b2_zombieParticle) != 0)
          {
              DestructionListener destructionListener =
                  m_world.m_destructionListener;
              if ((flags & ParticleType.b2_destructionListener) != 0 &&
                  destructionListener != null)
              {
                  destructionListener.SayGoodbye(i);
              }
              newIndices[i] = b2_invalidParticleIndex;
          }
          else
          {
              newIndices[i] = newCount;
              if (i != newCount)
              {
                  m_flagsBuffer.data[newCount] = m_flagsBuffer.data[i];
                  m_positionBuffer.data[newCount] = m_positionBuffer.data[i];
                  m_velocityBuffer.data[newCount] = m_velocityBuffer.data[i];
                  m_groupBuffer[newCount] = m_groupBuffer[i];
                  if (m_depthBuffer)
                  {
                      m_depthBuffer[newCount] = m_depthBuffer[i];
                  }
                  if (m_colorBuffer.data)
                  {
                      m_colorBuffer.data[newCount] = m_colorBuffer.data[i];
                  }
                  if (m_userDataBuffer.data)
                  {
                      m_userDataBuffer.data[newCount] = m_userDataBuffer.data[i];
                  }
              }
              newCount++;
          }
      }

      // predicate functions


      // update proxies
      for (int k = 0; k < m_proxyCount; k++)
      {
          Proxy& proxy = m_proxyBuffer[k];
          proxy.index = newIndices[proxy.index];
      }
      Proxy lastProxy = std.remove_if(
          m_proxyBuffer, m_proxyBuffer + m_proxyCount,
          Test.IsProxyInvalid);
      m_proxyCount = (int) (lastProxy - m_proxyBuffer);

      // update contacts
      for (int k = 0; k < m_contactCount; k++)
      {
          ParticleContact contact = m_contactBuffer[k];
          contact.indexA = newIndices[contact.indexA];
          contact.indexB = newIndices[contact.indexB];
      }
      ParticleContact lastContact = std.remove_if(
          m_contactBuffer, m_contactBuffer + m_contactCount,
          Test.IsContactInvalid);
      m_contactCount = (int) (lastContact - m_contactBuffer);

      // update particle-body contacts
      for (int k = 0; k < m_bodyContactCount; k++)
      {
          ParticleBodyContact contact = m_bodyContactBuffer[k];
          contact.index = newIndices[contact.index];
      }
      ParticleBodyContact lastBodyContact = std.remove_if(
          m_bodyContactBuffer, m_bodyContactBuffer + m_bodyContactCount,
          Test.IsBodyContactInvalid);
      m_bodyContactCount = (int) (lastBodyContact - m_bodyContactBuffer);

      // update pairs
      for (int k = 0; k < m_pairCount; k++)
      {
          Pair pair = m_pairBuffer[k];
          pair.indexA = newIndices[pair.indexA];
          pair.indexB = newIndices[pair.indexB];
      }
      Pair* lastPair = std.remove_if(
          m_pairBuffer, m_pairBuffer + m_pairCount, Test.IsPairInvalid);
      m_pairCount = (int) (lastPair - m_pairBuffer);

      // update triads
      for (int k = 0; k < m_triadCount; k++)
      {
          Triad& triad = m_triadBuffer[k];
          triad.indexA = newIndices[triad.indexA];
          triad.indexB = newIndices[triad.indexB];
          triad.indexC = newIndices[triad.indexC];
      }
      Triad lastTriad = std.remove_if(
          m_triadBuffer, m_triadBuffer + m_triadCount,
          Test.IsTriadInvalid);
      m_triadCount = (int) (lastTriad - m_triadBuffer);

      // update groups
      for (ParticleGroup* group = m_groupList; group; group = group.getNext())
      {
          int firstIndex = newCount;
          int lastIndex = 0;
          boolean modified = false;
          for (int i = group.m_firstIndex; i < group.m_lastIndex; i++)
          {
              int j = newIndices[i];
              if (j >= 0) {
                  firstIndex = b2Min(firstIndex, j);
                  lastIndex = b2Max(lastIndex, j + 1);
              } else {
                  modified = true;
              }
          }
          if (firstIndex < lastIndex)
          {
              group.m_firstIndex = firstIndex;
              group.m_lastIndex = lastIndex;
              if (modified)
              {
                  if (group.m_groupFlags & b2_rigidParticleGroup)
                  {
                      group.m_toBeSplit = true;
                  }
              }
          }
          else
          {
              group.m_firstIndex = 0;
              group.m_lastIndex = 0;
              if (group.m_destroyAutomatically)
              {
                  group.m_toBeDestroyed = true;
              }
          }
      }

      // update particle count
      m_count = newCount;
      m_world.m_stackAllocator.Free(newIndices);

      // destroy bodies with no particles
      for (ParticleGroup* group = m_groupList; group;)
      {
          ParticleGroup* next = group.getNext();
          if (group.m_toBeDestroyed)
          {
              DestroyParticleGroup(group);
          }
          else if (group.m_toBeSplit)
          {
              // TODO: split the group
          }
          group = next;
      }
  }

  void RotateBuffer(int start, int mid, int end)
  {
      // move the particles assigned to the given group toward the end of array
      if (start == mid || mid == end)
      {
          return;
      }
      struct NewIndices
      {
          int operator[](int i)
          {
              if (i < start)
              {
                  return i;
              }
              else if (i < mid)
              {
                  return i + end - mid;
              }
              else if (i < end)
              {
                  return i + start - mid;
              }
              else
              {
                  return i;
              }
          }
          int start, mid, end;
      } newIndices;
      newIndices.start = start;
      newIndices.mid = mid;
      newIndices.end = end;

      std.rotate(m_flagsBuffer.data + start, m_flagsBuffer.data + mid, m_flagsBuffer.data + end);
      std.rotate(m_positionBuffer.data + start, m_positionBuffer.data + mid, m_positionBuffer.data + end);
      std.rotate(m_velocityBuffer.data + start, m_velocityBuffer.data + mid, m_velocityBuffer.data + end);
      std.rotate(m_groupBuffer + start, m_groupBuffer + mid, m_groupBuffer + end);
      if (m_depthBuffer)
      {
          std.rotate(m_depthBuffer + start, m_depthBuffer + mid, m_depthBuffer + end);
      }
      if (m_colorBuffer.data)
      {
          std.rotate(m_colorBuffer.data + start, m_colorBuffer.data + mid, m_colorBuffer.data + end);
      }
      if (m_userDataBuffer.data)
      {
          std.rotate(m_userDataBuffer.data + start, m_userDataBuffer.data + mid, m_userDataBuffer.data + end);
      }

      // update proxies
      for (int k = 0; k < m_proxyCount; k++)
      {
          Proxy& proxy = m_proxyBuffer[k];
          proxy.index = newIndices[proxy.index];
      }

      // update contacts
      for (int k = 0; k < m_contactCount; k++)
      {
          ParticleContact contact = m_contactBuffer[k];
          contact.indexA = newIndices[contact.indexA];
          contact.indexB = newIndices[contact.indexB];
      }

      // update particle-body contacts
      for (int k = 0; k < m_bodyContactCount; k++)
      {
          ParticleBodyContact contact = m_bodyContactBuffer[k];
          contact.index = newIndices[contact.index];
      }

      // update pairs
      for (int k = 0; k < m_pairCount; k++)
      {
          Pair pair = m_pairBuffer[k];
          pair.indexA = newIndices[pair.indexA];
          pair.indexB = newIndices[pair.indexB];
      }

      // update triads
      for (int k = 0; k < m_triadCount; k++)
      {
          Triad& triad = m_triadBuffer[k];
          triad.indexA = newIndices[triad.indexA];
          triad.indexB = newIndices[triad.indexB];
          triad.indexC = newIndices[triad.indexC];
      }

      // update groups
      for (ParticleGroup* group = m_groupList; group; group = group.getNext())
      {
          group.m_firstIndex = newIndices[group.m_firstIndex];
          group.m_lastIndex = newIndices[group.m_lastIndex - 1] + 1;
      }
  }

  void setParticleRadius(float radius) {
    m_particleDiameter = 2 * radius;
    m_squaredDiameter = m_particleDiameter * m_particleDiameter;
    m_inverseDiameter = 1 / m_particleDiameter;
  }

  void setParticleDensity(float density) {
    m_density = density;
    m_inverseDensity = 1 / m_density;
  }

  float getParticleDensity() {
    return m_density;
  }

  void setParticleGravityScale(float gravityScale) {
    m_gravityScale = gravityScale;
  }

  float getParticleGravityScale() {
    return m_gravityScale;
  }

  void setParticleDamping(float damping) {
    m_dampingStrength = damping;
  }

  float getParticleDamping() {
    return m_dampingStrength;
  }

  float getParticleRadius() {
    return m_particleDiameter / 2;
  }

  float getCriticalVelocity(final TimeStep step) {
    return m_particleDiameter * step.inv_dt;
  }

  float getCriticalVelocitySquared(final TimeStep step) {
    float velocity = getCriticalVelocity(step);
    return velocity * velocity;
  }

  float getCriticalPressure(final TimeStep step) {
    return m_density * getCriticalVelocitySquared(step);
  }

  float getParticleStride() {
    return Settings.particleStride * m_particleDiameter;
  }

  float getParticleMass() {
    float stride = getParticleStride();
    return m_density * stride * stride;
  }

  float getParticleInvMass() {
    return 1.777777f * m_inverseDensity * m_inverseDiameter * m_inverseDiameter;
  }

  int[] getParticleFlagsBuffer() {
    return m_flagsBuffer.data;
  }

  Vec2[] getParticlePositionBuffer() {
    return m_positionBuffer.data;
  }

  Vec2[] getParticleVelocityBuffer() {
    return m_velocityBuffer.data;
  }

  ParticleColor[] getParticleColorBuffer() {
    m_colorBuffer.data = requestParticleBuffer(ParticleColor.class, m_colorBuffer.data);
    return m_colorBuffer.data;
  }

  Object[] getParticleUserDataBuffer() {
    m_userDataBuffer.data = requestParticleBuffer(Object.class, m_userDataBuffer.data);
    return m_userDataBuffer.data;
  }

  int getParticleMaxCount() {
    return m_maxCount;
  }

  void setParticleMaxCount(int count) {
    assert (m_count <= count);
    m_maxCount = count;
  }

  void setParticleBuffer(ParticleBufferInt buffer, int[] newData, int newCapacity) {
    assert ((newData != null && newCapacity != 0) || (newData == null && newCapacity == 0));
    if (buffer.userSuppliedCapacity != 0) {
      // m_world.m_blockAllocator.Free(buffer.data, sizeof(T) * m_internalAllocatedCapacity);
    }
    buffer.data = newData;
    buffer.userSuppliedCapacity = newCapacity;
  }

  <T> void setParticleBuffer(ParticleBuffer<T> buffer, T[] newData, int newCapacity) {
    assert ((newData != null && newCapacity != 0) || (newData == null && newCapacity == 0));
    if (buffer.userSuppliedCapacity != 0) {
      // m_world.m_blockAllocator.Free(buffer.data, sizeof(T) * m_internalAllocatedCapacity);
    }
    buffer.data = newData;
    buffer.userSuppliedCapacity = newCapacity;
  }

  void setParticleFlagsBuffer(int[] buffer, int capacity) {
    setParticleBuffer(m_flagsBuffer, buffer, capacity);
  }

  void setParticlePositionBuffer(Vec2[] buffer, int capacity) {
    setParticleBuffer(m_positionBuffer, buffer, capacity);
  }

  void setParticleVelocityBuffer(Vec2[] buffer, int capacity) {
    setParticleBuffer(m_velocityBuffer, buffer, capacity);
  }

  void setParticleColorBuffer(ParticleColor[] buffer, int capacity) {
    setParticleBuffer(m_colorBuffer, buffer, capacity);
  }

  ParticleGroup[] getParticleGroupBuffer() {
    return m_groupBuffer;
  }

  void setParticleUserDataBuffer(Object[] buffer, int capacity) {
    setParticleBuffer(m_userDataBuffer, buffer, capacity);
  }

  void QueryAABB(QueryCallback callback, final AABB aabb) {
    if (m_proxyCount == 0) {
      return;
    }
    Proxy beginProxy = m_proxyBuffer[0];
    Proxy endProxy = beginProxy + m_proxyCount;
    Proxy firstProxy =
        std.lower_bound(
            beginProxy,
            endProxy,
            computeTag(m_inverseDiameter * aabb.lowerBound.x, m_inverseDiameter * aabb.lowerBound.y));
    Proxy lastProxy =
        std.upper_bound(
            firstProxy,
            endProxy,
            computeTag(m_inverseDiameter * aabb.upperBound.x, m_inverseDiameter * aabb.upperBound.y));
    for (Proxy proxy = firstProxy; proxy < lastProxy; ++proxy) {
      int i = proxy.index;
      final Vec2 refp = m_positionBuffer.data[i];
      if (aabb.lowerBound.x < p.x && p.x < aabb.upperBound.x && aabb.lowerBound.y < p.y
          && p.y < aabb.upperBound.y) {
        if (!callback.ReportParticle(i)) {
          break;
        }
      }
    }
  }

  void rayCast(RayCastCallback callback, final Vec2 refpoint1, final Vec2 refpoint2) {
    if (m_proxyCount == 0) {
      return;
    }
    Proxy beginProxy = m_proxyBuffer;
    Proxy endProxy = beginProxy + m_proxyCount;
    Proxy firstProxy =
        std.lower_bound(
            beginProxy,
            endProxy,
            computeTag(m_inverseDiameter * b2Min(point1.x, point2.x) - 1, m_inverseDiameter
                * b2Min(point1.y, point2.y) - 1));
    Proxy lastProxy =
        std.upper_bound(
            firstProxy,
            endProxy,
            computeTag(m_inverseDiameter * b2Max(point1.x, point2.x) + 1, m_inverseDiameter
                * b2Max(point1.y, point2.y) + 1));
    float fraction = 1;
    // solving the following equation:
    // ((1-t)*point1+t*point2-position)^2=diameter^2
    // where t is a potential fraction
    Vec2 v = point2 - point1;
    float v2 = Vec2.dot(v, v);
    for (Proxy proxy = firstProxy; proxy < lastProxy; ++proxy) {
      int i = proxy.index;
      Vec2 p = point1 - m_positionBuffer.data[i];
      float pv = Vec2.dot(p, v);
      float p2 = Vec2.dot(p, p);
      float determinant = pv * pv - v2 * (p2 - m_squaredDiameter);
      if (determinant >= 0) {
        float sqrtDeterminant = Sqrt(determinant);
        // find a solution between 0 and fraction
        float t = (-pv - sqrtDeterminant) / v2;
        if (t > fraction) {
          continue;
        }
        if (t < 0) {
          t = (-pv + sqrtDeterminant) / v2;
          if (t < 0 || t > fraction) {
            continue;
          }
        }
        Vec2 n = p + t * v;
        n.Normalize();
        float f = callback.ReportParticle(i, point1 + t * v, n, t);
        fraction = b2Min(fraction, f);
        if (fraction <= 0) {
          break;
        }
      }
    }
  }

  float computeParticleCollisionEnergy() {
    float sum_v2 = 0;
    for (int k = 0; k < m_contactCount; k++) {
      final ParticleContact contact = m_contactBuffer[k];
      int a = contact.indexA;
      int b = contact.indexB;
      Vec2 n = contact.normal;
      Vec2 v = m_velocityBuffer.data[b] - m_velocityBuffer.data[a];
      float vn = Vec2.dot(v, n);
      if (vn < 0) {
        sum_v2 += vn * vn;
      }
    }
    return 0.5f * getParticleMass() * sum_v2;
  }

  // reallocate a buffer
  static <T> T[] reallocateBuffer(ParticleBuffer<T> buffer, int oldCapacity, int newCapacity,
      boolean deferred) {
    assert (newCapacity > oldCapacity);
    return BufferUtils.reallocateBuffer(buffer.dataClass, buffer.data, buffer.userSuppliedCapacity,
        oldCapacity, newCapacity, deferred);
  }

  static int[] reallocateBuffer(ParticleBufferInt buffer, int oldCapacity, int newCapacity,
      boolean deferred) {
    assert (newCapacity > oldCapacity);
    return BufferUtils.reallocateBuffer(buffer.data, buffer.userSuppliedCapacity, oldCapacity,
        newCapacity, deferred);
  }

  @SuppressWarnings("unchecked")
  <T> T[] requestParticleBuffer(Class<T> klass, T[] buffer) {
    if (buffer == null) {
      buffer = (T[]) Array.newInstance(klass, m_internalAllocatedCapacity);
    }
    return buffer;
  }

  float[] requestParticleBuffer(float[] buffer) {
    if (buffer == null) {
      buffer = new float[m_internalAllocatedCapacity];
    }
    return buffer;
  }

  static class ParticleBuffer<T> {
    T[] data;
    final Class<T> dataClass;
    int userSuppliedCapacity;

    public ParticleBuffer(Class<T> dataClass) {
      this.dataClass = dataClass;
    }
  }
  static class ParticleBufferInt {
    int[] data;
    int userSuppliedCapacity;
  }

  /** Used for detecting particle contacts */
  static class Proxy implements Comparable<Proxy> {
    int index;
    int tag;

    @Override
    public int compareTo(Proxy o) {
      return tag - o.tag;
    }
  }

  /** Connection between two particles */
  static class Pair {
    int indexA, indexB;
    int flags;
    float strength;
    float distance;
  }

  /** Connection between three particles */
  static class Triad {
    int indexA, indexB, indexC;
    int flags;
    float strength;
    final Vec2 pa = new Vec2(), pb = new Vec2(), pc = new Vec2();
    float ka, kb, kc, s;
  }

  // Callback used with VoronoiDiagram.
  static class CreateParticleGroupCallback implements VoronoiDiagramCallback {
    public void callback(int a, int b, int c) {
      final Vec2 refpa = system.m_positionBuffer.data[a];
      final Vec2 refpb = system.m_positionBuffer.data[b];
      final Vec2 refpc = system.m_positionBuffer.data[c];
      Vec2 dab = pa - pb;
      Vec2 dbc = pb - pc;
      Vec2 dca = pc - pa;
      float maxDistanceSquared = b2_maxTriadDistanceSquared * system.m_squaredDiameter;
      if (Vec2.dot(dab, dab) < maxDistanceSquared && Vec2.dot(dbc, dbc) < maxDistanceSquared
          && Vec2.dot(dca, dca) < maxDistanceSquared) {
        if (system.m_triadCount >= system.m_triadCapacity) {
          int oldCapacity = system.m_triadCapacity;
          int newCapacity =
              system.m_triadCount ? 2 * system.m_triadCount : b2_minParticleBufferCapacity;
          system.m_triadBuffer =
              system.ReallocateBuffer(system.m_triadBuffer, oldCapacity, newCapacity);
          system.m_triadCapacity = newCapacity;
        }
        Triad triad = system.m_triadBuffer[system.m_triadCount];
        triad.indexA = a;
        triad.indexB = b;
        triad.indexC = c;
        triad.flags =
            system.m_flagsBuffer.data[a] | system.m_flagsBuffer.data[b]
                | system.m_flagsBuffer.data[c];
        triad.strength = def.strength;
        Vec2 midPoint = (float) 1 / 3 * (pa + pb + pc);
        triad.pa = pa - midPoint;
        triad.pb = pb - midPoint;
        triad.pc = pc - midPoint;
        triad.ka = -Vec2.dot(dca, dab);
        triad.kb = -Vec2.dot(dab, dbc);
        triad.kc = -Vec2.dot(dbc, dca);
        triad.s = Vec2.cross(pa, pb) + Vec2.cross(pb, pc) + Vec2.cross(pc, pa);
        system.m_triadCount++;
      }
    }

    ParticleSystem system;
    ParticleGroupDef def; // pointer
    int firstIndex;
  }

  // Callback used with VoronoiDiagram.
  static class JoinParticleGroupsCallback implements VoronoiDiagramCallback {
    public void callback(int a, int b, int c) {
      // Create a triad if it will contain particles from both groups.
      int countA =
          (a < groupB.m_firstIndex) +
          (b < groupB.m_firstIndex) +
          (c < groupB.m_firstIndex);
      if (countA > 0 && countA < 3)
      {
          int af = system.m_flagsBuffer.data[a];
          int bf = system.m_flagsBuffer.data[b];
          int cf = system.m_flagsBuffer.data[c];
          if (af & bf & cf & k_triadFlags)
          {
              final Vec2 refpa = system.m_positionBuffer.data[a];
              final Vec2 refpb = system.m_positionBuffer.data[b];
              final Vec2 refpc = system.m_positionBuffer.data[c];
              Vec2 dab = pa - pb;
              Vec2 dbc = pb - pc;
              Vec2 dca = pc - pa;
              float maxDistanceSquared = b2_maxTriadDistanceSquared * system.m_squaredDiameter;
              if (Vec2.dot(dab, dab) < maxDistanceSquared &&
                  Vec2.dot(dbc, dbc) < maxDistanceSquared &&
                  Vec2.dot(dca, dca) < maxDistanceSquared)
              {
                  if (system.m_triadCount >= system.m_triadCapacity)
                  {
                      int oldCapacity = system.m_triadCapacity;
                      int newCapacity = system.m_triadCount ? 2 * system.m_triadCount : b2_minParticleBufferCapacity;
                      system.m_triadBuffer = system.ReallocateBuffer(system.m_triadBuffer, oldCapacity, newCapacity);
                      system.m_triadCapacity = newCapacity;
                  }
                  Triad triad = system.m_triadBuffer[system.m_triadCount];
                  triad.indexA = a;
                  triad.indexB = b;
                  triad.indexC = c;
                  triad.flags = af | bf | cf;
                  triad.strength = b2Min(groupA.m_strength, groupB.m_strength);
                  Vec2 midPoint = (float) 1 / 3 * (pa + pb + pc);
                  triad.pa = pa - midPoint;
                  triad.pb = pb - midPoint;
                  triad.pc = pc - midPoint;
                  triad.ka = -Vec2.dot(dca, dab);
                  triad.kb = -Vec2.dot(dab, dbc);
                  triad.kc = -Vec2.dot(dbc, dca);
                  triad.s = Vec2.cross(pa, pb) + Vec2.cross(pb, pc) + Vec2.cross(pc, pa);
                  system.m_triadCount++;
              }
          }
      }
    }

    ParticleSystem system;
    ParticleGroup groupA;
    ParticleGroup groupB;
  };

  static class DestroyParticlesInShapeCallback implements QueryCallback {
    final ParticleSystem system;
    final Shape shape;
    final Transform xf;
    final boolean callDestructionListener;
    int destroyed;

    public DestroyParticlesInShapeCallback(ParticleSystem system, Shape shape, Transform xf,
        boolean callDestructionListener) {
      this.system = system;
      this.shape = shape;
      this.xf = xf;
      this.callDestructionListener = callDestructionListener;
    }

    @Override
    public boolean reportFixture(Fixture fixture) {
      return false;
    }

    public boolean reportParticle(int index) {
      assert (index >= 0 && index < system.m_count);
      if (shape.testPoint(xf, system.m_positionBuffer.data[index])) {
        system.destroyParticle(index, callDestructionListener);
        destroyed++;
      }
      return true;
    }
  }

  static class UpdateBodyContactsCallback implements QueryCallback {
    ParticleSystem system;

    @Override
    public boolean reportFixture(Fixture fixture) {
      if (fixture.IsSensor())
      {
          return true;
      }
      final Shape shape = fixture.getShape();
      Body b = fixture.getBody();
      Vec2 bp = b.getWorldCenter();
      float bm = b.getMass();
      float bI = b.getInertia() - bm * b.getLocalCenter().LengthSquared();
      float invBm = bm > 0 ? 1 / bm : 0;
      float invBI = bI > 0 ? 1 / bI : 0;
      int childCount = shape.getChildCount();
      for (int childIndex = 0; childIndex < childCount; childIndex++)
      {
          AABB aabb = fixture.getAABB(childIndex);
          aabb.lowerBound.x -= m_system.m_particleDiameter;
          aabb.lowerBound.y -= m_system.m_particleDiameter;
          aabb.upperBound.x += m_system.m_particleDiameter;
          aabb.upperBound.y += m_system.m_particleDiameter;
          Proxy beginProxy = m_system.m_proxyBuffer;
          Proxy endProxy = beginProxy + m_system.m_proxyCount;
          Proxy firstProxy = std.lower_bound(
              beginProxy, endProxy,
              computeTag(
                  m_system.m_inverseDiameter * aabb.lowerBound.x,
                  m_system.m_inverseDiameter * aabb.lowerBound.y));
          Proxy lastProxy = std.upper_bound(
              firstProxy, endProxy,
              computeTag(
                  m_system.m_inverseDiameter * aabb.upperBound.x,
                  m_system.m_inverseDiameter * aabb.upperBound.y));
          for (Proxy proxy = firstProxy; proxy != lastProxy; ++proxy)
          {
              int a = proxy.index;
              Vec2 ap = m_system.m_positionBuffer.data[a];
              if (aabb.lowerBound.x <= ap.x && ap.x <= aabb.upperBound.x &&
                  aabb.lowerBound.y <= ap.y && ap.y <= aabb.upperBound.y)
              {
                  float d;
                  Vec2 n;
                  fixture.ComputeDistance(ap, &d, &n, childIndex);
                  if (d < m_system.m_particleDiameter)
                  {
                      float invAm =
                          m_system.m_flagsBuffer.data[a] & b2_wallParticle ?
                          0 : m_system.getParticleInvMass();
                      Vec2 rp = ap - bp;
                      float rpn = Vec2.cross(rp, n);
                      if (m_system.m_bodyContactCount >= m_system.m_bodyContactCapacity)
                      {
                          int oldCapacity = m_system.m_bodyContactCapacity;
                          int newCapacity = m_system.m_bodyContactCount ? 2 * m_system.m_bodyContactCount : b2_minParticleBufferCapacity;
                          m_system.m_bodyContactBuffer = m_system.ReallocateBuffer(m_system.m_bodyContactBuffer, oldCapacity, newCapacity);
                          m_system.m_bodyContactCapacity = newCapacity;
                      }
                      ParticleBodyContact contact = m_system.m_bodyContactBuffer[m_system.m_bodyContactCount];
                      contact.index = a;
                      contact.body = b;
                      contact.weight = 1 - d * m_system.m_inverseDiameter;
                      contact.normal = -n;
                      contact.mass = 1 / (invAm + invBm + invBI * rpn * rpn);
                      m_system.m_bodyContactCount++;
                  }
              }
          }
      }
      return true;
    }
  }

  static class SolveCollisionCallback implements QueryCallback {
    ParticleSystem system;
    TimeStep step;

    @Override
    public boolean reportFixture(Fixture fixture) {
      if (fixture.IsSensor()) {
        return true;
      }
      final Shape shape = fixture.getShape();
      Body body = fixture.getBody();
      Proxy beginProxy = m_system.m_proxyBuffer;
      Proxy endProxy = beginProxy + m_system.m_proxyCount;
      int childCount = shape.getChildCount();
      for (int childIndex = 0; childIndex < childCount; childIndex++) {
        AABB aabb = fixture.getAABB(childIndex);
        aabb.lowerBound.x -= m_system.m_particleDiameter;
        aabb.lowerBound.y -= m_system.m_particleDiameter;
        aabb.upperBound.x += m_system.m_particleDiameter;
        aabb.upperBound.y += m_system.m_particleDiameter;
        Proxy firstProxy =
            std.lower_bound(
                beginProxy,
                endProxy,
                computeTag(m_system.m_inverseDiameter * aabb.lowerBound.x,
                    m_system.m_inverseDiameter * aabb.lowerBound.y));
        Proxy lastProxy =
            std.upper_bound(
                firstProxy,
                endProxy,
                computeTag(m_system.m_inverseDiameter * aabb.upperBound.x,
                    m_system.m_inverseDiameter * aabb.upperBound.y));
        for (Proxy proxy = firstProxy; proxy != lastProxy; ++proxy) {
          int a = proxy.index;
          Vec2 ap = m_system.m_positionBuffer.data[a];
          if (aabb.lowerBound.x <= ap.x && ap.x <= aabb.upperBound.x && aabb.lowerBound.y <= ap.y
              && ap.y <= aabb.upperBound.y) {
            Vec2 av = m_system.m_velocityBuffer.data[a];
            b2RayCastOutput output;
            b2RayCastInput input;
            input.p1 = b2Mul(body.m_xf, b2MulT(body.m_xf0, ap));
            input.p2 = ap + m_step.dt * av;
            input.maxFraction = 1;
            if (fixture.RayCast(output, input, childIndex)) {
              Vec2 p =
                  (1 - output.fraction) * input.p1 + output.fraction * input.p2 + b2_linearSlop
                      * output.normal;
              Vec2 v = m_step.inv_dt * (p - ap);
              m_system.m_velocityBuffer.data[a] = v;
              Vec2 f = m_system.getParticleMass() * (av - v);
              f = Vec2.dot(f, output.normal) * output.normal;
              body.ApplyLinearImpulse(f, p, true);
            }
          }
        }
      }
      return true;
    }
  }

  static class Test {
    static boolean IsProxyInvalid(final Proxy proxy) {
      return proxy.index < 0;
    }

    static boolean IsContactInvalid(final ParticleContact contact) {
      return contact.indexA < 0 || contact.indexB < 0;
    }

    static boolean IsBodyContactInvalid(final ParticleBodyContact contact) {
      return contact.index < 0;
    }

    static boolean IsPairInvalid(final Pair pair) {
      return pair.indexA < 0 || pair.indexB < 0;
    }

    static boolean IsTriadInvalid(final Triad triad) {
      return triad.indexA < 0 || triad.indexB < 0 || triad.indexC < 0;
    }
  };
}
