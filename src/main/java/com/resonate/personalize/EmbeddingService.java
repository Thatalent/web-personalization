package com.resonate.personalize;

import org.springframework.ai.ollama.OllamaEmbeddingModel;
import org.springframework.stereotype.Service;

@Service
public class EmbeddingService {
  private final OllamaEmbeddingModel embedding;

  public EmbeddingService(OllamaEmbeddingModel embedding) { this.embedding = embedding; }

  public float[] embedText(String text) {
    return embedding.embed(text);
  }
}
