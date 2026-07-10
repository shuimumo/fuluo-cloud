package com.shuimumu.fuluogame.service;

import com.shuimumu.fuluogame.entity.Topic;
import com.shuimumu.fuluogame.vo.PageVO;
import com.shuimumu.fuluogame.vo.PostContentVO;

import java.util.List;

public interface TopicService {
    List<Topic> list();
    PageVO<PostContentVO> getPosts(Long topicId, String tab, int page, int size);
}
