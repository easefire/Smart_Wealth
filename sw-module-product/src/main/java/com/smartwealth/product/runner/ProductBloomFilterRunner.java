package com.smartwealth.product.runner;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.smartwealth.product.entity.ProdInfo;
import com.smartwealth.product.mapper.ProdInfoMapper;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@Slf4j
public class ProductBloomFilterRunner implements CommandLineRunner {

    @Autowired
    private RedissonClient redissonClient;
    @Autowired
    private ProdInfoMapper prodInfoMapper; // 假设你有 Mapper

    public static final String BLOOM_FILTER_NAME = "prod:id:bloom:filter";

    @Override
    public void run(String... args) throws Exception {
        RBloomFilter<Long> bloomFilter = redissonClient.getBloomFilter(BLOOM_FILTER_NAME);
        boolean exists = bloomFilter.tryInit(1000000L, 0.01);

        if (exists) {
            List<Long> allProdIds = prodInfoMapper.selectList(new LambdaQueryWrapper<ProdInfo>()
                            .select(ProdInfo::getId)
                            .eq(ProdInfo::getStatus, 1))
                    .stream().map(ProdInfo::getId).toList();

            for (Long id : allProdIds) {
                bloomFilter.add(id);
            }
        }
    }
}