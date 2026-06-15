package com.acmp.compute.mapper;

import com.acmp.compute.entity.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Optional;

@Mapper
public interface UserMapper {
    Optional<User> findById(@Param("id") String id);
    Optional<User> findByUsername(@Param("username") String username);
    int insert(User user);
    int update(User user);
    java.util.List<User> findAll();
}
