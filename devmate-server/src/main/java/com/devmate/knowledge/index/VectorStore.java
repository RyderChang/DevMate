package com.devmate.knowledge.index;

import java.util.List;

/** Typed complete-source boundary; never accepts a caller-supplied filter. */
public interface VectorStore {
    void upsert(IndexWork work,List<IndexPoint> points,List<float[]> vectors);
    boolean matches(IndexWork work,List<IndexPoint> points);
    boolean deleteAndVerify(IndexWork work,List<IndexPoint> points);
}
