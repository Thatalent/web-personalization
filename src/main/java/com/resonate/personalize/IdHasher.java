package com.resonate.personalize;

import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Random;

@Component
public class IdHasher {
  private static final int D = 64;

  public float[] embedIds(List<String> ids) {
    float[] v = new float[D];
    for (String id : ids) add(v, hashToUnit(id));
    normalize(v);
    return v;
  }

  private float[] hashToUnit(String id) {
    float[] out = new float[D];
    long seed = (id.hashCode() * 0x9E3779B97F4A7C15L);
    Random rnd = new Random(seed);
    double norm = 0;
    for (int i = 0; i < D; i++) { out[i] = (float) rnd.nextGaussian(); norm += out[i]*out[i]; }
    float inv = (float)(1.0 / Math.sqrt(norm));
    for (int i = 0; i < D; i++) out[i] *= inv;
    return out;
  }

  private void add(float[] a, float[] b){ for (int i=0;i<a.length;i++) a[i]+=b[i]; }
  private void normalize(float[] a){
    double n=0; for(float x:a) n+=x*x;
    if (n>0) { float inv=(float)(1.0/Math.sqrt(n)); for (int i=0;i<a.length;i++) a[i]*=inv; }
  }
}
