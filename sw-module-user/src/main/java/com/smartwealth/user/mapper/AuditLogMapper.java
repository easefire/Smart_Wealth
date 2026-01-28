package com.smartwealth.user.mapper;


import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.smartwealth.user.entity.AuditLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/**
 * 审计日志 Mapper 接口
 */
@Mapper
public interface AuditLogMapper extends BaseMapper<AuditLog> {

    @Select("SELECT * FROM t_admin_audit_log ORDER BY create_time DESC")
    IPage<AuditLog> selectAuditLogs(Page<AuditLog> page);

    int insert(AuditLog entity);

}
