package com.shuimumu.fuluogame.service;

import com.shuimumu.fuluogame.dto.UserDTO;
import com.shuimumu.fuluogame.vo.*;
import java.util.List;
import java.util.Map;
import java.util.Set;

public interface UserService {

    UserVO getProfile(Long userId);

    Map<Long, UserVO> batchGetProfiles(Set<Long> userIds);

    void updateProfile(UserDTO userDTO);

    UserProfileVO getPublicProfile(Long userId, Long currentUserId);

    void follow(Long followerId, Long followedId);

    void unfollow(Long followerId, Long followedId);

    PageVO<PostContentVO> getUserPosts(Long userId, int page, int size);

    PageVO<CommentVO> getUserComments(Long userId, int page, int size);

    PageVO<PostContentVO> getUserLiked(Long userId, int page, int size);

    PageVO<FollowVO> getFollowList(Long userId, String type, int page, int size);

    void checkPrivacy(Long userId, String field);

    PageVO<PostContentVO> getInbox(Long userId, int page, int size);
}
