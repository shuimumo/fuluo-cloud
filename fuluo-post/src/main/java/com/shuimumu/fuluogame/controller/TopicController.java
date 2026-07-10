package com.shuimumu.fuluogame.controller;

import com.shuimumu.fuluogame.entity.Topic;
import com.shuimumu.fuluogame.service.TopicService;
import com.shuimumu.fuluogame.vo.PageVO;
import com.shuimumu.fuluogame.vo.PostContentVO;
import com.shuimumu.fuluogame.vo.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "话题接口")
@RestController
@RequestMapping("/topic")
public class TopicController {

    @Autowired
    private TopicService topicService;

    @GetMapping("/list")
    public Result<List<Topic>> list() {
        return Result.success(topicService.list());
    }

    @GetMapping("/{id}/posts")
    public Result<PageVO<PostContentVO>> posts(
            @PathVariable Long id,
            @RequestParam(defaultValue = "latest") String tab,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        PageVO<PostContentVO> pageVO = topicService.getPosts(id, tab, page, size);
        return Result.success(pageVO);
    }
}
