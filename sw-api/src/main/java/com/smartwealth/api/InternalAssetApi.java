package com.smartwealth.api;

import com.smartwealth.common.dto.PurchaseMessageDTO;
import com.smartwealth.common.dto.RedemptionMessageDTO;
import com.smartwealth.common.exception.BusinessException;

import java.math.BigDecimal;

/**
 * 资产模块对外 SPI 契约。
 *
 * <p>本接口位于 sw-api：
 *   - 不依赖 sw-module-asset；
 *   - 调用方（trade / wealth）通过本接口与资产模块交互，不再直接 import 资产实现类。
 *   - 接口里只暴露<strong>对外业务能力</strong>，其他内部 helper（按 entity 查询、分页、流水读模型等）
 *     仍保留在 {@code com.smartwealth.asset.service.impl.InternalAssetService}，因为它们引用
 *     业务 entity / mybatis-plus Wrapper，不适合作为 SPI 暴露。
 *
 * <p><strong>实现类</strong>：{@code com.smartwealth.asset.service.impl.InternalAssetService}（@Service）。
 */
public interface InternalAssetApi {

    /**
     * 取整个平台所有用户钱包的总余额。用于运营报表/对账场景。
     *
     * @return 总余额，{@code null} 时由实现自行兜底为 {@link BigDecimal#ZERO}
     */
    BigDecimal getTotalWalletBalance();

    /**
     * 在<strong>当前事务内</strong>对指定用户的钱包行加 X 锁（{@code SELECT ... FOR UPDATE}）。
     * 主要给 trade 模块在赎回流程预占资源时使用。
     *
     * <p>注意：必须由调用方包在 {@code @Transactional} 中，否则锁会立即释放。
     */
    void selectprelock(Long userId);

    /**
     * 校验用户支付密码（赎回/支付类强敏感操作前置）。
     *
     * <p>语义严格区分三种失败：钱包不存在 / 未设支付密码 / 密码错误。
     * 失败一律抛 {@link BusinessException}，由调用方捕获翻译为前端 Result。
     *
     * @throws BusinessException 失败时携带具体 ResultCode
     */
    void verifyPayPassword(Long userId, String rawPassword);

    /**
     * 申购扣款入账：根据 MQ 消息扣减用户钱包、写流水、注册"提交后回调"通知 trade。
     * 内部已包含幂等保护（按 orderId 去重）和悲观+乐观双重并发控制。
     */
    void deductForPurchase(PurchaseMessageDTO msg);

    /**
     * 赎回入账：根据 MQ 消息为用户增加余额，并按"本金 + 收益"两条流水分别落账。
     * 内部已包含幂等保护（按 requestId 去重）。
     */
    void processRedemption(RedemptionMessageDTO msg);
}
