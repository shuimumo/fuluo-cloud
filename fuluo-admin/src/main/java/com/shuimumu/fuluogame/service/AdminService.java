package com.shuimumu.fuluogame.service;

import com.shuimumu.fuluogame.entity.Announcement;
import com.shuimumu.fuluogame.entity.Banner;
import com.shuimumu.fuluogame.entity.Comment;
import com.shuimumu.fuluogame.entity.Post;
import com.shuimumu.fuluogame.entity.Topic;
import com.shuimumu.fuluogame.vo.PageVO;

import java.util.List;

public interface AdminService {
    List<Banner> listBanners();
    void saveBanner(Banner banner);
    void updateBanner(Banner banner);
    void deleteBanner(Long id);

    List<Announcement> listAnnouncements();
    void saveAnnouncement(Announcement announcement);
    void updateAnnouncement(Announcement announcement);
    void deleteAnnouncement(Long id);

    void saveTopic(Topic topic);
    void updateTopic(Topic topic);
    void deleteTopic(Long id);

    PageVO<Post> listPosts(Integer status, int page, int size);
    PageVO<Comment> listComments(Integer status, int page, int size);
    void approvePost(Long postId);
    void rejectPost(Long postId, String reason);
    void approveComment(Long commentId);
    void rejectComment(Long commentId, String reason);
}
