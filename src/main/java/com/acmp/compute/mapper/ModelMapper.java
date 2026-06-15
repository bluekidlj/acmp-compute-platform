package com.acmp.compute.mapper;

import com.acmp.compute.entity.Model;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ModelMapper {
    int insert(Model model);
    int update(Model model);
    Model findById(@Param("id") String id);
    Model findByName(@Param("name") String name);
    List<Model> findAll();
    int deleteById(@Param("id") String id);
}
