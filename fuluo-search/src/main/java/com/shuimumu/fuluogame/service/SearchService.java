package com.shuimumu.fuluogame.service;

import com.shuimumu.fuluogame.vo.PageVO;
import com.shuimumu.fuluogame.vo.PostContentVO;

public interface SearchService {
    PageVO<PostContentVO> searchPosts(String q, int page, int size);
}
