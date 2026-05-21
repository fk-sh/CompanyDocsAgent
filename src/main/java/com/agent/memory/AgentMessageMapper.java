package com.agent.memory;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * AgentMessagePO 的 MyBatis-Plus Mapper 接口。
 * <p>
 * 继承 {@link BaseMapper} 后自动获得全部 CRUD 方法，零 SQL。
 * 主要被 {@link MysqlMessageStore} 调用：
 * <ul>
 *   <li>{@code insert(AgentMessagePO)} — 消息写入</li>
 *   <li>{@code selectList(Wrapper)} — 按 sessionId + 时间排序查询</li>
 *   <li>{@code selectCount(Wrapper)} — 统计某会话消息数</li>
 *   <li>{@code delete(Wrapper)} — 按 sessionId 批量删除</li>
 * </ul>
 *
 * @see AgentMessagePO
 * @see MysqlMessageStore
 */
@Mapper
public interface AgentMessageMapper extends BaseMapper<AgentMessagePO> {
}
