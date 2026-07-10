package com.shuimumu.fuluogame.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PostPersistMsg {
    private Long postId;
    private Long userId;
    private Boolean isNew;    // true=新帖 false=编辑
    private String title;
    private String content;
    private String images;    // JSON数组字符串，编辑时非null才更新
    private String topicIds;  // 逗号分隔，仅新帖，编辑时null
}
