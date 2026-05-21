package com.agent.memory;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * AgentSession 的 MyBatis-Plus Mapper 接口。
 * <p>
 * 继承 {@link BaseMapper} 后自动获得以下方法（零 SQL）：
 * <ul>
 *   <li>{@code insert(AgentSession)} — 插入</li>
 *   <li>{@code selectById(String)} — 按主键查询</li>
 *   <li>{@code selectList(Wrapper)} — 条件查询</li>
 *   <li>{@code update(Wrapper)} — 条件更新</li>
 *   <li>{@code deleteById(String)} — 按主键删除</li>
 * </ul>
 * {@code @Mapper} 注解让 MyBatis-Plus 自动扫描并注册为 Spring Bean，无需额外 {@code @MapperScan}。
 *
 * @see AgentSession
 * @see MysqlSessionStore
 */
@Mapper
public interface AgentSessionMapper extends BaseMapper<AgentSession> {
}
