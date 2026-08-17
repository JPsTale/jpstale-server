package org.jpstale.server.game.service;

import lombok.extern.slf4j.Slf4j;
import org.jpstale.dao.userdb.entity.CharacterInfo;
import org.jpstale.dao.userdb.entity.UserInfo;
import org.jpstale.dao.userdb.mapper.CharacterInfoMapper;
import org.jpstale.dao.userdb.mapper.UserInfoMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.List;

/**
 * 账号服务
 * 处理登录验证、角色管理等
 */
@Slf4j
@Service
public class AccountService {

    @Autowired
    private UserInfoMapper userInfoMapper;

    @Autowired
    private CharacterInfoMapper characterInfoMapper;

    /**
     * 根据账号名查找用户
     */
    public UserInfo findByUsername(String username) {
        return userInfoMapper.selectOneByAccountName(username);
    }

    /**
     * 根据ID查找用户
     */
    public UserInfo findById(Integer userId) {
        return userInfoMapper.selectById(userId);
    }

    /**
     * 根据ID查找角色
     */
    public CharacterInfo getCharacterById(Integer characterId) {
        return characterInfoMapper.selectById(characterId);
    }

    /**
     * 验证密码
     */
    public boolean verifyPassword(UserInfo user, String password) {
        if (user == null || user.getPassword() == null) {
            return false;
        }
        // 数据库中存储的是 Base64 编码的 MD5 哈希
        String hashedPassword = hashPassword(password);
        return user.getPassword().equals(hashedPassword);
    }

    /**
     * 检查账号是否被封禁
     */
    public boolean isBanned(Integer userId) {
        if (userId == null) return false;
        UserInfo user = userInfoMapper.selectById(userId);
        return user != null && user.getBanStatus() != null && user.getBanStatus() > 0;
    }

    /**
     * 获取角色列表
     */
    public List<CharacterInfo> getCharacters(String accountName) {
        return characterInfoMapper.selectList(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<CharacterInfo>()
                .eq(CharacterInfo::getAccountName, accountName)
        );
    }

    /**
     * 获取角色数量
     */
    public long getCharacterCount(String accountName) {
        return characterInfoMapper.selectCount(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<CharacterInfo>()
                .eq(CharacterInfo::getAccountName, accountName)
        );
    }

    /**
     * 检查角色名是否已存在
     */
    public boolean isCharacterNameExists(String name) {
        return characterInfoMapper.selectCount(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<CharacterInfo>()
                .eq(CharacterInfo::getName, name)
        ) > 0;
    }

    /**
     * 创建角色
     */
    public CharacterInfo createCharacter(String accountName, String name, int jobCode) {
        CharacterInfo character = new CharacterInfo();
        character.setAccountName(accountName);
        character.setName(name);
        character.setJobCode(jobCode);
        character.setLevel(1);
        character.setExperience(0L);
        character.setGold(0);
        character.setLastStage(1); // 默认地图
        character.setIsOnline(0);
        character.setSeasonal(0);
        character.setGmLevel(0);
        character.setBanned(0);

        characterInfoMapper.insert(character);
        log.info("Created character: {} for account: {}", name, accountName);
        return character;
    }

    /**
     * 检查角色是否属于该账号
     */
    public boolean isCharacterOwned(String accountName, Integer characterId) {
        CharacterInfo character = characterInfoMapper.selectById(characterId);
        return character != null && accountName.equals(character.getAccountName());
    }

    /**
     * 密码哈希（MD5 + Base64）
     */
    private String hashPassword(String password) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(password.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("MD5 algorithm not found", e);
        }
    }
}
