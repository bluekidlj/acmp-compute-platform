package com.acmp.compute.mapper;

import com.acmp.compute.entity.Model;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ModelMapper {
    void insert(Model model);
    void update(Model model);
    Model findById(@Param("id") String id);
    Model findByName(@Param("name") String name);
    List<Model> findAll();
    void deleteById(@Param("id") String id);
}