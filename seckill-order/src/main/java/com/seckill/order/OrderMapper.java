package com.seckill.order;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 订单 Mapper——MyBatis-Plus {@link BaseMapper} 提供标准 CRUD，
 * 额外提供 {@code SELECT ... FOR UPDATE} 作为 Redisson 不可用时的降级兜底。
 *
 * @author TianJunQi
 * @since 2026-05-12
 */
@Mapper
public interface OrderMapper extends BaseMapper<Order> {

    /**
     * 悲观锁查询——用于 Redis 宕机时对订单状态流转做行级锁保护。
     *
     * @param id 订单 ID
     * @return 被行锁锁定的订单，或 null
     * @since 2026-05-12
     */
    @Select("SELECT * FROM seckill_order WHERE id = #{id} FOR UPDATE")
    Order selectByIdForUpdate(@Param("id") Long id);
}
