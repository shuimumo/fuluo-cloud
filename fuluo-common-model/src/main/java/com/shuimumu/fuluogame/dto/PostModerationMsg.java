package com.shuimumu.fuluogame.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PostModerationMsg {
    private Long postId;
    private Long userId;
    private Boolean isNew;    // true=新帖 false=编辑，新帖审核通过后incrAuthorStats
    private String topicIds;  // 逗号分隔，新帖审核通过后HINCRBY话题计数，编辑时null
}
