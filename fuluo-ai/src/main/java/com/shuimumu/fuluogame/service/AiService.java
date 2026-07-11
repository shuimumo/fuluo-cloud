package com.shuimumu.fuluogame.service;

import reactor.core.publisher.Flux;

public interface AiService {
    Flux<String> summarise(Long postId);
    String getPrompt(Long postId);
    Flux<String> polish(String text);
}
