package com.resonate.personalize;

public class PgVectorUtils {
  public static String toPgVector(float[] v) {
    StringBuilder sb = new StringBuilder("[");
    for (int i = 0; i < v.length; i++) {
      if (i > 0) sb.append(',');
      sb.append(Float.toString(v[i]));
    }
    sb.append(']');
    return sb.toString();
  }
}
