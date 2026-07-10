package com.shuimumu.fuluogame.dto;

import lombok.Data;

import java.util.List;

@Data
public class PostDTO {

    private String title;

    private String content;

    private List<String> images;

    private List<Long> topicIds;
}
