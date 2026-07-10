package com.shuimumu.fuluogame.service;

import com.shuimumu.fuluogame.dto.LoginDTO;
import java.util.Map;

public interface LoginService {
    String login(LoginDTO loginDTO);

    void sendCode(String phone);

    Map<String, Object> loginByPassword(String phone, String hashedPassword);

    void setPassword(Long userId, String hashedPassword);
}
