package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private RedissonClient redissonClient;

    @Override
    @Transactional
    public Result seckillVoucher(Long voucherId) {
        //1.查询优惠券
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        //2.判断秒杀是否开始
        if(voucher.getBeginTime().isAfter(LocalDateTime.now())){
            return Result.fail("秒杀尚未开始!");
        }
        //3.判断秒杀是否结束
        if(voucher.getEndTime().isBefore(LocalDateTime.now())){
            return Result.fail("秒杀已经结束!");
        }
        //4.判断库存是否充足
        if(voucher.getStock() < 1){
            return Result.fail("库存不足!");
        }

        return createVoucherOrder(voucherId);
    }

    //会出现并发安全问题，当某个用户同时进来一百个线程时，查到该用户购买的优惠券都为0时，会进行下面操作，导致一个人购买了多张优惠券
    //synchronized是加在方法上，还是方法内部
    //如果是加在方法上，则表示锁住是当前类对象，则当不同的用户来的时候，都会被锁住
    //应该加在方法内部，锁住用户id，只有相同的用户来了，才被锁住
//    @Transactional
//    public Result createVoucherOrder(Long voucherId) {
//        //5.一人一单
//        Long userId = UserHolder.getUser().getId();
//
//        //我们期望id值一样的作为一把锁，每一个请求来,userId都是一个全新的user对象
//        //因此用userId作为锁对象，锁不住
//        //用userId.toString()还是锁不住，因为toString()还是创建了新对象
//        //用intern()方法，从字符串常量池中取值
//        synchronized (userId.toString().intern()){
//        //5.1 查询订单
//        Integer count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
//        //5.2判断是否存在
//        if(count > 0){
//            //用户购买过
//            return Result.fail("用户已经购买过一次!");
//        }
//
//        //6.扣减库存
//        boolean success = seckillVoucherService.update()
//                .setSql("stock = stock -1")
//                .eq("voucher_id", voucherId).gt("stock", 0)
//                .update();
//        if(!success){
//            return Result.fail("库存不足!");
//        }
//        //7.创建订单
//        VoucherOrder voucherOrder = new VoucherOrder();
//        //7.1订单id
//        long orderId = redisIdWorker.nextId("order");
//        voucherOrder.setId(orderId);
//        //7.2用户
//        voucherOrder.setUserId(userId);
//        //7.3代金券
//        voucherOrder.setVoucherId(voucherId);
//        save(voucherOrder);
//        //7.返回订单id
//        return Result.ok(orderId);
//        }
//    }

    @Resource
    private StringRedisTemplate stringRedisTemplate;

//    @Transactional
//    public Result createVoucherOrder(Long voucherId) {
//        //5.一人一单
//        Long userId = UserHolder.getUser().getId();
//
//        //创建锁对象
//        SimpleRedisLock redisLock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
//        //尝试获取锁
//        boolean isLock = redisLock.tryLock(1200);
//        //判断
//        if(!isLock){
//            //获取锁失败，直接返回失败或者重试
//            return Result.fail("不允许重复下单!");
//        }
//
//        try {
//            //5.1 查询订单
//            int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
//            //5.2判断是否存在
//            if(count > 0){
//                //用户购买过
//                return Result.fail("用户已经购买过一次!");
//            }
//
//            //6.扣减库存
//            boolean success = seckillVoucherService.update()
//                    .setSql("stock = stock -1")
//                    .eq("voucher_id", voucherId).gt("stock", 0)
//                    .update();
//            if(!success){
//                return Result.fail("库存不足!");
//            }
//            //7.创建订单
//            VoucherOrder voucherOrder = new VoucherOrder();
//            //7.1订单id
//            long orderId = redisIdWorker.nextId("order");
//            voucherOrder.setId(orderId);
//            //7.2用户
//            voucherOrder.setUserId(userId);
//            //7.3代金券
//            voucherOrder.setVoucherId(voucherId);
//            save(voucherOrder);
//            //7.返回订单id
//            return Result.ok(orderId);
//        }finally {
//            //释放锁
//            redisLock.unlock();
//        }
//
//    }
@Transactional
public Result createVoucherOrder(Long voucherId) {
    //5.一人一单
    Long userId = UserHolder.getUser().getId();

    //创建锁对象
    RLock redisLock = redissonClient.getLock("lock:order:" + userId);

    //尝试获取锁
    //三种模式
    //空参 waitTime默认是-1，不等待，获取失败立即结束，释放时间默认30s
    boolean isLock = redisLock.tryLock();
    //判断
    if(!isLock){
        //获取锁失败，直接返回失败或者重试
        return Result.fail("不允许重复下单!");
    }

    try {
        //5.1 查询订单
        int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        //5.2判断是否存在
        if(count > 0){
            //用户购买过
            return Result.fail("用户已经购买过一次!");
        }

        //6.扣减库存
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock -1")
                .eq("voucher_id", voucherId).gt("stock", 0)
                .update();
        if(!success){
            return Result.fail("库存不足!");
        }
        //7.创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        //7.1订单id
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        //7.2用户
        voucherOrder.setUserId(userId);
        //7.3代金券
        voucherOrder.setVoucherId(voucherId);
        save(voucherOrder);
        //7.返回订单id
        return Result.ok(orderId);
    }finally {
        //释放锁
        redisLock.unlock();
    }

}
}
