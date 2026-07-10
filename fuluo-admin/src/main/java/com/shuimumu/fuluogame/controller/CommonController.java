package com.shuimumu.fuluogame.controller;

import com.shuimumu.fuluogame.service.CommonService;
import com.shuimumu.fuluogame.vo.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@RestController
@Tag(name = "公共模块")
@RequestMapping("/common")
public class CommonController {

    @Autowired
    private CommonService commonService;

    @Operation(summary = "文件上传")
    @PostMapping("/upload")
    public Result<Map<String, String>> upload(@RequestParam("file") MultipartFile file) {
        String url = commonService.upload(file);
        return Result.success(Map.of("url", url));
    }
}
