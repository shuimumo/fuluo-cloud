package com.shuimumu.fuluogame.service.Impl;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.shuimumu.fuluogame.constants.PostRedisKeys;
import com.shuimumu.fuluogame.constants.SystemRedisKeys;
import com.shuimumu.fuluogame.constants.UserRedisKeys;
import com.shuimumu.fuluogame.dto.UserDTO;
import com.shuimumu.fuluogame.entity.*;
import com.shuimumu.fuluogame.exception.BusinessException;
import com.shuimumu.fuluogame.interceptor.UserContext;
import com.shuimumu.fuluogame.mapper.*;
import com.shuimumu.fuluogame.service.UserService;
import com.shuimumu.fuluogame.utils.CacheClient;
import com.shuimumu.fuluogame.utils.PostAssembler;
import com.shuimumu.fuluogame.utils.LocalCache;
import com.shuimumu.fuluogame.utils.RedisUtils;
import com.shuimumu.fuluogame.vo.*;
import lombok.extern.slf4j.Slf4j;
import com.shuimumu.fuluogame.config.RabbitMQConfig;
import com.shuimumu.fuluogame.dto.SyncMessage;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.zset.Aggregate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements UserService {

    private static final DefaultRedisScript<Long> FOLLOW_SCRIPT = new DefaultRedisScript<>();
    private static final DefaultRedisScript<Long> UNFOLLOW_SCRIPT = new DefaultRedisScript<>();

    static {
        FOLLOW_SCRIPT.setLocation(new ClassPathResource("lua/follow.lua"));
        FOLLOW_SCRIPT.setResultType(Long.class);
        UNFOLLOW_SCRIPT.setLocation(new ClassPathResource("lua/unfollow.lua"));
        UNFOLLOW_SCRIPT.setResultType(Long.class);
    }

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private CacheClient cacheClient;
    @Autowired
    private LocalCache localCache;

    @Autowired
    private PostMapper postMapper;

    @Autowired
    private CommentMapper commentMapper;

    @Autowired
    private UserStatMapper userStatMapper;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private PostAssembler postAssembler;

    @Override
    public UserVO getProfile(Long userId) {
        String cacheKey = SystemRedisKeys.cacheUser(userId);
        UserVO local = localCache.get(cacheKey);
        if (local != null) return local;
        String json = stringRedisTemplate.opsForValue().get(cacheKey);
        UserVO vo;
        if (json != null && !json.isEmpty()) {
            vo = JSONUtil.toBean(json, UserVO.class);
            stringRedisTemplate.expire(cacheKey, UserRedisKeys.CACHE_USER_TTL, TimeUnit.MINUTES);
        } else {
            vo = cacheClient.queryWithMutex("lock:" + cacheKey, cacheKey, UserVO.class, () -> {
                User user = query().eq("id", userId).one();
                if (user == null) throw new BusinessException("用户不存在");
                UserVO v = new UserVO();
                BeanUtils.copyProperties(user, v);
                return v;
            }, UserRedisKeys.CACHE_USER_TTL, TimeUnit.MINUTES);
        }

        long[] stats = getStats(userId);
        vo.setPosts(stats[0]);
        vo.setFollowing(stats[1]);
        vo.setFollowers(stats[2]);
        vo.setLikes(stats[3]);
        localCache.put(cacheKey, vo);
        return vo;
    }
    // 批量获取用户信息，用于填充评论、帖子等场景的头像、用户名等信息

    
    @Override
    public Map<Long, UserVO> batchGetProfiles(Set<Long> userIds) {
        if (userIds.isEmpty()) return Map.of();
        List<String> keys = userIds.stream()
                .map(SystemRedisKeys::cacheUser).collect(Collectors.toList());
        List<String> jsons = stringRedisTemplate.opsForValue().multiGet(keys);
        Map<Long, UserVO> result = new HashMap<>();
        List<Long> missIds = new ArrayList<>();
        int i = 0;
        for (Long uid : userIds) {
            String json = jsons != null ? jsons.get(i++) : null;
            if (json != null && !json.isEmpty()) {
                result.put(uid, JSONUtil.toBean(json, UserVO.class));
                stringRedisTemplate.expire(SystemRedisKeys.cacheUser(uid),
                        UserRedisKeys.CACHE_USER_TTL, TimeUnit.MINUTES);
            } else {
                missIds.add(uid);
            }
        }
        for (Long uid : missIds) {
            String dataKey = SystemRedisKeys.cacheUser(uid);
            UserVO uvo = cacheClient.queryWithMutex("lock:" + dataKey, dataKey, UserVO.class, () -> {
                User u = userMapper.selectById(uid);
                if (u == null) return null;
                UserVO v = new UserVO();
                BeanUtils.copyProperties(u, v);
                return v;
            }, UserRedisKeys.CACHE_USER_TTL, TimeUnit.MINUTES);
            if (uvo != null) result.put(uid, uvo);
        }
        return result;
    }

    @Override
    public void updateProfile(UserDTO userDTO) {
        Long userId = UserContext.getUser().getId();
        User user = getById(userId);
        if (user == null) {
            throw new BusinessException("用户不存在");
        }
        if (userDTO.getUsername() != null) {
            user.setUsername(userDTO.getUsername());
        }
        if (userDTO.getAvatar() != null) {
            user.setAvatar(userDTO.getAvatar());
        }
        if (userDTO.getGender() != null) {
            user.setGender(userDTO.getGender());
        }
        if (userDTO.getBirthday() != null) {
            user.setBirthday(userDTO.getBirthday());
        }
        if (userDTO.getPostsPublic() != null) {
            user.setPostsPublic(userDTO.getPostsPublic());
        }
        if (userDTO.getLikedPublic() != null) {
            user.setLikedPublic(userDTO.getLikedPublic());
        }
        if (userDTO.getFollowingPublic() != null) {
            user.setFollowingPublic(userDTO.getFollowingPublic());
        }
        if (userDTO.getFollowersPublic() != null) {
            user.setFollowersPublic(userDTO.getFollowersPublic());
        }
        updateById(user);
        UserVO updatedVO = new UserVO();
        BeanUtils.copyProperties(user, updatedVO);
        cacheClient.set(SystemRedisKeys.cacheUser(userId),
                JSONUtil.toJsonStr(updatedVO),
                UserRedisKeys.CACHE_USER_TTL, TimeUnit.MINUTES);
        cacheClient.delete(SystemRedisKeys.cacheUserProfile(userId));
        localCache.broadcastInvalidate(SystemRedisKeys.cacheUser(userId));
    }

    @Override
    public PageVO<PostContentVO> getUserPosts(Long userId, int page, int size) {
        Long currentUserId = null;
        try { currentUserId = UserContext.getUser().getId(); } catch (Exception ignored) {}
        // 作者看自己的帖子：ZSET + MGET
        if (userId.equals(currentUserId)) {
            String zsetKey = PostRedisKeys.userPostsZset(userId);
            long start = (long) (page - 1) * size;
            long end = start + size - 1;
            Set<String> members = stringRedisTemplate.opsForZSet().reverseRange(zsetKey, start, end);
            if (members == null || members.isEmpty()) {
                return PageVO.of(List.of(), 0L, page, size);
            }
            List<Long> postIds = members.stream().map(Long::parseLong).collect(Collectors.toList());
            List<String> cacheKeys = postIds.stream()
                    .map(SystemRedisKeys::cachePostContent).collect(Collectors.toList());
            List<String> cachedJsons = stringRedisTemplate.opsForValue().multiGet(cacheKeys);
            Map<Long, PostContentVO> resultMap = new LinkedHashMap<>();
            List<Long> missIds = new ArrayList<>();
            for (int i = 0; i < postIds.size(); i++) {
                Long pid = postIds.get(i);
                String json = cachedJsons != null ? cachedJsons.get(i) : null;
                if (json != null && !json.isEmpty()) {
                    resultMap.put(pid, JSONUtil.toBean(json, PostContentVO.class));
                    stringRedisTemplate.expire(SystemRedisKeys.cachePostContent(pid),
                            PostRedisKeys.POST_CONTENT_CACHE_TTL, TimeUnit.MINUTES);
                } else {
                    missIds.add(pid);
                }
            }
            for (Long pid : missIds) {
                String dataKey = SystemRedisKeys.cachePostContent(pid);
                PostContentVO vo = cacheClient.queryWithMutex("lock:" + dataKey, dataKey, PostContentVO.class, () -> {
                    Post p = postMapper.selectById(pid);
                    if (p == null) return null;
                    return postAssembler.toVO(p);
                }, PostRedisKeys.POST_CONTENT_CACHE_TTL, TimeUnit.MINUTES);
                if (vo != null) resultMap.put(pid, vo);
            }
            List<PostContentVO> records = new ArrayList<>();
            for (Long pid : postIds) {
                PostContentVO vo = resultMap.get(pid);
                if (vo != null) records.add(vo);
            }
            postAssembler.fillUsers(records, batchGetProfiles(
                    records.stream().map(PostContentVO::getUserId).filter(Objects::nonNull).collect(Collectors.toSet())));
            postAssembler.fillCounts(records);
            postAssembler.fillStatus(records);
            Long total = stringRedisTemplate.opsForZSet().zCard(zsetKey);
            return PageVO.of(records, total != null ? total : 0L, page, size);
        }
        // 其他人只能看 status=0：ZSET + MGET，过滤非公开帖
        String zsetKey = PostRedisKeys.userPostsZset(userId);
        long start = (long) (page - 1) * size;
        long end = start + size - 1;
        Set<String> members = stringRedisTemplate.opsForZSet().reverseRange(zsetKey, start, end);
        if (members == null || members.isEmpty()) {
            return PageVO.of(List.of(), 0L, page, size);
        }
        List<Long> postIds = members.stream().map(Long::parseLong).collect(Collectors.toList());
        List<String> cacheKeys = postIds.stream()
                .map(SystemRedisKeys::cachePostContent).collect(Collectors.toList());
        List<String> cachedJsons = stringRedisTemplate.opsForValue().multiGet(cacheKeys);
        Map<Long, PostContentVO> resultMap = new LinkedHashMap<>();
        List<Long> missIds = new ArrayList<>();
        for (int i = 0; i < postIds.size(); i++) {
            Long pid = postIds.get(i);
            String json = cachedJsons != null ? cachedJsons.get(i) : null;
            if (json != null && !json.isEmpty()) {
                resultMap.put(pid, JSONUtil.toBean(json, PostContentVO.class));
                stringRedisTemplate.expire(SystemRedisKeys.cachePostContent(pid),
                        PostRedisKeys.POST_CONTENT_CACHE_TTL, TimeUnit.MINUTES);
            } else {
                missIds.add(pid);
            }
        }
        for (Long pid : missIds) {
            String dataKey = SystemRedisKeys.cachePostContent(pid);
            PostContentVO vo = cacheClient.queryWithMutex("lock:" + dataKey, dataKey, PostContentVO.class, () -> {
                Post p = postMapper.selectById(pid);
                if (p == null || p.getStatus() != 0) return null;
                return postAssembler.toVO(p);
            }, PostRedisKeys.POST_CONTENT_CACHE_TTL, TimeUnit.MINUTES);
            if (vo != null) resultMap.put(pid, vo);
        }
        List<PostContentVO> records = new ArrayList<>();
        for (Long pid : postIds) {
            PostContentVO vo = resultMap.get(pid);
            if (vo != null) records.add(vo);
        }
        postAssembler.fillUsers(records, batchGetProfiles(
                records.stream().map(PostContentVO::getUserId).filter(Objects::nonNull).collect(Collectors.toSet())));
        postAssembler.fillCounts(records);
        postAssembler.fillStatus(records);
        Long total = stringRedisTemplate.opsForZSet().zCard(zsetKey);
        return PageVO.of(records, total != null ? total : 0L, page, size);
    }

    @Override
    public PageVO<CommentVO> getUserComments(Long userId, int page, int size) {
        Page<Comment> result = commentMapper.selectPage(new Page<>(page, size),
                new LambdaQueryWrapper<Comment>().eq(Comment::getUserId, userId).orderByDesc(Comment::getCreateTime));
        List<CommentVO> records = result.getRecords().stream().map(c -> {
            CommentVO vo = new CommentVO();
            BeanUtils.copyProperties(c, vo);
            return vo;
        }).collect(Collectors.toList());
        return PageVO.of(records, result.getTotal(), page, size);
    }

    @Override
    public PageVO<PostContentVO> getUserLiked(Long userId, int page, int size) {
        String key = UserRedisKeys.liked(userId);
        long start = (long) (page - 1) * size;
        long end = start + size - 1;
        Set<String> members = stringRedisTemplate.opsForZSet().reverseRange(key, start, end);
        if (members == null || members.isEmpty()) return PageVO.of(List.of(), 0, page, size);
        List<Long> postIds = members.stream().map(Long::parseLong).collect(Collectors.toList());
        // MGET 单帖缓存
        List<String> cacheKeys = postIds.stream()
                .map(SystemRedisKeys::cachePostContent).collect(Collectors.toList());
        List<String> cachedJsons = stringRedisTemplate.opsForValue().multiGet(cacheKeys);
        Map<Long, PostContentVO> resultMap = new LinkedHashMap<>();
        List<Long> missIds = new ArrayList<>();
        for (int i = 0; i < postIds.size(); i++) {
            String json = cachedJsons != null ? cachedJsons.get(i) : null;
            if (json != null && !json.isEmpty()) {
                resultMap.put(postIds.get(i), JSONUtil.toBean(json, PostContentVO.class));
                stringRedisTemplate.expire(SystemRedisKeys.cachePostContent(postIds.get(i)),
                        PostRedisKeys.POST_CONTENT_CACHE_TTL, TimeUnit.MINUTES);
            } else {
                missIds.add(postIds.get(i));
            }
        }
        for (Long pid : missIds) {
            String dataKey = SystemRedisKeys.cachePostContent(pid);
            PostContentVO vo = cacheClient.queryWithMutex("lock:" + dataKey, dataKey, PostContentVO.class, () -> {
                Post p = postMapper.selectById(pid);
                if (p == null || p.getStatus() != 0) return null;
                return postAssembler.toVO(p);
            }, PostRedisKeys.POST_CONTENT_CACHE_TTL, TimeUnit.MINUTES);
            if (vo != null) resultMap.put(pid, vo);
        }
        List<PostContentVO> records = new ArrayList<>();
        for (Long pid : postIds) {
            PostContentVO vo = resultMap.get(pid);
            if (vo != null) records.add(vo);
        }
        postAssembler.fillUsers(records, batchGetProfiles(
                records.stream().map(PostContentVO::getUserId).filter(Objects::nonNull).collect(Collectors.toSet())));
        postAssembler.fillCounts(records);
        postAssembler.fillStatus(records);
        Long total = stringRedisTemplate.opsForZSet().zCard(key);
        return PageVO.of(records, total != null ? total : 0L, page, size);
    }

    @Override
    public PageVO<FollowVO> getFollowList(Long userId, String type, int page, int size) {
        String key = "following".equals(type)
                ? UserRedisKeys.following(userId)
                : UserRedisKeys.followers(userId);

        Set<String> members = stringRedisTemplate.opsForSet().members(key);
        if (members == null || members.isEmpty()) {
            return PageVO.of(List.of(), 0, page, size);
        }

        List<String> allIds = new ArrayList<>(members);
        long total = allIds.size();
        int from = (page - 1) * size;
        int to = Math.min(from + size, allIds.size());
        if (from >= allIds.size()) {
            return PageVO.of(List.of(), total, page, size);
        }

        List<Long> pageIdList = allIds.subList(from, to).stream()
                .map(Long::parseLong).collect(Collectors.toList());
        Map<Long, UserVO> profileMap = batchGetProfiles(new HashSet<>(pageIdList));

        List<FollowVO> records = pageIdList.stream()
                .map(id -> {
                    FollowVO vo = new FollowVO();
                    vo.setId(id);
                    UserVO u = profileMap.get(id);
                    if (u != null) {
                        vo.setNickname(u.getUsername());
                        vo.setUsername(u.getUsername());
                        vo.setAvatar(u.getAvatar());
                    }
                    return vo;
                })
                .collect(Collectors.toList());

        return PageVO.of(records, total, page, size);
    }

    @Override
    public UserProfileVO getPublicProfile(Long userId, Long currentUserId) {
        String cacheKey = SystemRedisKeys.cacheUserProfile(userId);
        String lockKey = "lock:" + cacheKey;

        UserProfileVO vo = cacheClient.queryWithMutex(lockKey, cacheKey, UserProfileVO.class, () -> {
            User user = userMapper.selectById(userId);
            if (user == null) return null;
            UserProfileVO v = new UserProfileVO();
            v.setId(user.getId());
            v.setUsername(user.getUsername());
            v.setAvatar(user.getAvatar());
            v.setGender(user.getGender());
            v.setBirthday(user.getBirthday());
            v.setPostsPublic(user.getPostsPublic());
            v.setLikedPublic(user.getLikedPublic());
            v.setFollowingPublic(user.getFollowingPublic());
            v.setFollowersPublic(user.getFollowersPublic());
            long[] stats = getStats(userId);
            v.setPosts(stats[0]);
            v.setFollowing(stats[1]);
            v.setFollowers(stats[2]);
            v.setLikes(stats[3]);
            return v;
        }, UserRedisKeys.CACHE_USER_PROFILE_TTL, TimeUnit.MINUTES);

        if (vo == null) throw new BusinessException("用户不存在");

        if (currentUserId != null) {
            vo.setFollowed(Boolean.TRUE.equals(
                    stringRedisTemplate.opsForSet().isMember(
                            UserRedisKeys.following(currentUserId),
                            userId.toString())));
        }
        return vo;
    }

    @Override
    public void follow(Long followerId, Long followedId) {
        if (followerId.equals(followedId)) {
            throw new BusinessException("不能关注自己");
        }
        List<String> keys = List.of(
                UserRedisKeys.following(followerId),
                UserRedisKeys.followers(followedId),
                UserRedisKeys.stats(followerId),
                UserRedisKeys.stats(followedId)
        );
        Long result = stringRedisTemplate.execute(FOLLOW_SCRIPT, keys,
                followedId.toString(), followerId.toString());
        if (result == null || result == 0) return;

        rabbitTemplate.convertAndSend(RabbitMQConfig.EXCHANGE, RabbitMQConfig.KEY_FOLLOW,
                new SyncMessage("follow", followerId, followedId, null));
        String fk = UserRedisKeys.stats(followerId);
        String tk = UserRedisKeys.stats(followedId);
        stringRedisTemplate.expire(fk, UserRedisKeys.USER_STATS_TTL, TimeUnit.DAYS);
        stringRedisTemplate.expire(tk, UserRedisKeys.USER_STATS_TTL, TimeUnit.DAYS);
        stringRedisTemplate.opsForSet().add(UserRedisKeys.statsDirty(),
                followerId.toString(), followedId.toString());
    }

    @Override
    public void unfollow(Long followerId, Long followedId) {
        List<String> keys = List.of(
                UserRedisKeys.following(followerId),
                UserRedisKeys.followers(followedId),
                UserRedisKeys.stats(followerId),
                UserRedisKeys.stats(followedId)
        );
        Long result = stringRedisTemplate.execute(UNFOLLOW_SCRIPT, keys,
                followedId.toString(), followerId.toString());
        if (result == null || result == 0) return;

        rabbitTemplate.convertAndSend(RabbitMQConfig.EXCHANGE, RabbitMQConfig.KEY_FOLLOW,
                new SyncMessage("unfollow", followerId, followedId, null));
        String fk = UserRedisKeys.stats(followerId);
        String tk = UserRedisKeys.stats(followedId);
        stringRedisTemplate.expire(fk, UserRedisKeys.USER_STATS_TTL, TimeUnit.DAYS);
        stringRedisTemplate.expire(tk, UserRedisKeys.USER_STATS_TTL, TimeUnit.DAYS);
        stringRedisTemplate.opsForSet().add(UserRedisKeys.statsDirty(),
                followerId.toString(), followedId.toString());
    }

    private long[] getStats(Long userId) {
        String key = UserRedisKeys.stats(userId);
        java.util.Map<Object, Object> map = stringRedisTemplate.opsForHash().entries(key);
        if (!map.isEmpty()) {
            return new long[]{RedisUtils.toLong(map.get("posts")), RedisUtils.toLong(map.get("following")),
                    RedisUtils.toLong(map.get("followers")), RedisUtils.toLong(map.get("likes"))};
        }
        UserStat stat = userStatMapper.selectById(userId);
        if (stat == null) return new long[]{0, 0, 0, 0};
        long[] stats = new long[]{stat.getPosts(), stat.getFollowing(), stat.getFollowers(), stat.getLikes()};
        stringRedisTemplate.opsForHash().putAll(key, Map.of(
                "posts", String.valueOf(stats[0]),
                "following", String.valueOf(stats[1]),
                "followers", String.valueOf(stats[2]),
                "likes", String.valueOf(stats[3])
        ));
        stringRedisTemplate.expire(key, UserRedisKeys.USER_STATS_TTL, TimeUnit.DAYS);
        return stats;
    }

    @Override
    public void checkPrivacy(Long userId, String field) {
        String cacheKey = SystemRedisKeys.cacheUserProfile(userId);
        String json = stringRedisTemplate.opsForValue().get(cacheKey);
        UserProfileVO cached = null;
        if (json != null && !json.isEmpty()) {
            cached = JSONUtil.toBean(json, UserProfileVO.class);
        }

        boolean visible;
        if (cached != null) {
            visible = switch (field) {
                case "posts" -> cached.getPostsPublic() == null || cached.getPostsPublic() == 1;
                case "liked" -> cached.getLikedPublic() == null || cached.getLikedPublic() == 1;
                case "following" -> cached.getFollowingPublic() == null || cached.getFollowingPublic() == 1;
                case "followers" -> cached.getFollowersPublic() == null || cached.getFollowersPublic() == 1;
                default -> true;
            };
        } else {
            User user = getById(userId);
            if (user == null) throw new BusinessException("用户不存在");
            visible = switch (field) {
                case "posts" -> user.getPostsPublic() == null || user.getPostsPublic() == 1;
                case "liked" -> user.getLikedPublic() == null || user.getLikedPublic() == 1;
                case "following" -> user.getFollowingPublic() == null || user.getFollowingPublic() == 1;
                case "followers" -> user.getFollowersPublic() == null || user.getFollowersPublic() == 1;
                default -> true;
            };
        }

        if (!visible) {
            throw new BusinessException("该用户未公开" + field);
        }
    }

    @Override
    public PageVO<PostContentVO> getInbox(Long userId, int page, int size) {
        // 打活跃标记（仅刷新时间，不触发补拉；补拉只在登录时做）
        stringRedisTemplate.opsForZSet().add(UserRedisKeys.activeUsers(),
                userId.toString(), System.currentTimeMillis() / 1000);

        String inboxKey = UserRedisKeys.inbox(userId);
        String mergedKey = UserRedisKeys.feedMerged(userId);

        // 仅首屏或 merged key 缺失时重建并集，翻页直接复用
        if (page == 1 || Boolean.FALSE.equals(stringRedisTemplate.hasKey(mergedKey))) {
            // 关注 ∩ 大V → outbox keys
            Set<String> bigVs = stringRedisTemplate.opsForSet().intersect(
                    UserRedisKeys.following(userId), UserRedisKeys.bigV());
            List<String> outboxKeys = new java.util.ArrayList<>();
            if (bigVs != null) {
                for (String bigVId : bigVs) {
                    outboxKeys.add(UserRedisKeys.outbox(Long.parseLong(bigVId)));
                }
            }

            // ZUNIONSTORE inbox ∪ outboxes，AGGREGATE MAX 防同帖在两个源中 score 加和
            stringRedisTemplate.opsForZSet().unionAndStore(
                    inboxKey, outboxKeys, mergedKey, Aggregate.MAX);
            stringRedisTemplate.expire(mergedKey, 90, TimeUnit.SECONDS);
        }

        long start = (long) (page - 1) * size;
        long end = start + size - 1;
        Set<String> members = stringRedisTemplate.opsForZSet().reverseRange(mergedKey, start, end);
        if (members == null || members.isEmpty()) {
            return PageVO.of(List.of(), 0L, page, size);
        }
        List<Long> postIds = members.stream().map(Long::parseLong).collect(Collectors.toList());
        List<String> cacheKeys = postIds.stream()
                .map(SystemRedisKeys::cachePostContent).collect(Collectors.toList());
        List<String> cachedJsons = stringRedisTemplate.opsForValue().multiGet(cacheKeys);
        Map<Long, PostContentVO> resultMap = new LinkedHashMap<>();
        List<Long> missIds = new ArrayList<>();
        for (int i = 0; i < postIds.size(); i++) {
            Long pid = postIds.get(i);
            String json = cachedJsons != null ? cachedJsons.get(i) : null;
            if (json != null && !json.isEmpty()) {
                resultMap.put(pid, JSONUtil.toBean(json, PostContentVO.class));
                stringRedisTemplate.expire(SystemRedisKeys.cachePostContent(pid),
                        PostRedisKeys.POST_CONTENT_CACHE_TTL, TimeUnit.MINUTES);
            } else {
                missIds.add(pid);
            }
        }
        for (Long pid : missIds) {
            String dataKey = SystemRedisKeys.cachePostContent(pid);
            PostContentVO vo = cacheClient.queryWithMutex("lock:" + dataKey, dataKey, PostContentVO.class, () -> {
                Post p = postMapper.selectById(pid);
                if (p == null || p.getStatus() != 0) return null;
                return postAssembler.toVO(p);
            }, PostRedisKeys.POST_CONTENT_CACHE_TTL, TimeUnit.MINUTES);
            if (vo != null) resultMap.put(pid, vo);
        }
        List<PostContentVO> records = new ArrayList<>();
        for (Long pid : postIds) {
            PostContentVO vo = resultMap.get(pid);
            if (vo != null) records.add(vo);
        }
        postAssembler.fillUsers(records, batchGetProfiles(
                records.stream().map(PostContentVO::getUserId).filter(Objects::nonNull).collect(Collectors.toSet())));
        postAssembler.fillCounts(records);
        postAssembler.fillStatus(records);
        Long total = stringRedisTemplate.opsForZSet().zCard(mergedKey);
        return PageVO.of(records, total != null ? total : 0L, page, size);
    }

}
