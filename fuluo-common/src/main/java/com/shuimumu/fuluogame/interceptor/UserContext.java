package com.shuimumu.fuluogame.interceptor;

import com.shuimumu.fuluogame.vo.UserVO;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

public class UserContext {

    public static UserVO getUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof UserVO) {
            return (UserVO) auth.getPrincipal();
        }
        return null;
    }
}
