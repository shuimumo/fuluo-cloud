package com.shuimumu.fuluogame.controller;

import com.shuimumu.fuluogame.service.AiService;
import com.shuimumu.fuluogame.vo.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/ai")
@Tag(name = "AI相关接口")
public class AiController {

    @Autowired
    private AiService aiService;

    @Operation(summary = "AI总结帖子和高赞评论")
    @GetMapping("/summarise")
    public Flux<String> summarise(Long postId) {
        return aiService.summarise(postId);
    }

    @Operation(summary = "预览prompt（调试用）")
    @GetMapping("/prompt")
    public Result<String> prompt(Long postId) {
        return Result.success(aiService.getPrompt(postId));
    }

    @Operation(summary = "润色文本")
    @PostMapping("/polish")
    public Flux<String> polish(@RequestBody String text) {
        return aiService.polish(text);
    }
}
