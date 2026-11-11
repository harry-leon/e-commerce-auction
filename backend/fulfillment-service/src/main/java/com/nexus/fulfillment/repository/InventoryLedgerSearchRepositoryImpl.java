package com.nexus.fulfillment.repository;

import com.nexus.fulfillment.entity.Inventory;
import com.nexus.fulfillment.entity.InventoryLedger;
import com.nexus.fulfillment.entity.Warehouse;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * Cài đặt truy vấn động cho Sổ cái tồn kho bất biến bằng JPA Criteria API.
 * Luồng: dựng Predicate an toàn kiểu dữ liệu cho PostgreSQL/H2 -> fetch inventory & warehouse -> đếm tổng bản ghi -> trả Page.
 * Quy tắc: chỉ đọc (SELECT), không cung cấp thao tác sửa/xóa bản ghi sổ cái.
 */
public class InventoryLedgerSearchRepositoryImpl implements InventoryLedgerSearchRepository {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public Page<InventoryLedger> searchLedgerFiltered(LedgerFilter filter, Pageable pageable) {
        if (filter.restrictToSkuIds() && (filter.skuIds() == null || filter.skuIds().isEmpty())) {
            return Page.empty(pageable);
        }

        CriteriaBuilder cb = entityManager.getCriteriaBuilder();

        // Truy vấn dữ liệu trang hiện tại kèm join fetch inventory và warehouse để tránh N+1 query
        CriteriaQuery<InventoryLedger> dataQuery = cb.createQuery(InventoryLedger.class);
        Root<InventoryLedger> root = dataQuery.from(InventoryLedger.class);
        root.fetch("inventory", JoinType.INNER).fetch("warehouse", JoinType.INNER);

        Path<Inventory> invPath = root.get("inventory");
        Path<Warehouse> whPath = invPath.get("warehouse");
        List<Predicate> dataPredicates = buildPredicates(cb, root, invPath, whPath, filter);
        dataQuery.where(dataPredicates.toArray(Predicate[]::new));
        dataQuery.orderBy(resolveOrders(cb, root, pageable));

        TypedQuery<InventoryLedger> query = entityManager.createQuery(dataQuery);
        if (pageable.isPaged()) {
            query.setFirstResult((int) pageable.getOffset());
            query.setMaxResults(pageable.getPageSize());
        }
        List<InventoryLedger> content = query.getResultList();

        // Truy vấn đếm tổng số bản ghi khớp điều kiện lọc
        CriteriaQuery<Long> countQuery = cb.createQuery(Long.class);
        Root<InventoryLedger> countRoot = countQuery.from(InventoryLedger.class);
        Path<Inventory> countInv = countRoot.get("inventory");
        Path<Warehouse> countWh = countInv.get("warehouse");
        List<Predicate> countPredicates = buildPredicates(cb, countRoot, countInv, countWh, filter);
        countQuery.select(cb.count(countRoot)).where(countPredicates.toArray(Predicate[]::new));
        long total = entityManager.createQuery(countQuery).getSingleResult();

        return new PageImpl<>(content, pageable, total);
    }

    private List<Predicate> buildPredicates(
            CriteriaBuilder cb,
            Root<InventoryLedger> root,
            Path<Inventory> invJoin,
            Path<Warehouse> whJoin,
            LedgerFilter filter
    ) {
        List<Predicate> predicates = new ArrayList<>();
        if (filter.skuId() != null) {
            predicates.add(cb.equal(invJoin.get("skuId"), filter.skuId()));
        }
        if (filter.restrictToSkuIds() && filter.skuIds() != null && !filter.skuIds().isEmpty()) {
            predicates.add(invJoin.get("skuId").in(filter.skuIds()));
        }
        if (filter.warehouseId() != null) {
            predicates.add(cb.equal(whJoin.get("id"), filter.warehouseId()));
        }
        if (filter.movementType() != null) {
            predicates.add(cb.equal(root.get("movementType"), filter.movementType()));
        }
        if (filter.refType() != null && !filter.refType().isBlank()) {
            predicates.add(cb.equal(cb.upper(root.get("refType")), filter.refType().trim().toUpperCase()));
        }
        if (filter.refId() != null) {
            predicates.add(cb.equal(root.get("refId"), filter.refId()));
        }
        if (filter.fromTime() != null) {
            predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), filter.fromTime()));
        }
        if (filter.toTime() != null) {
            predicates.add(cb.lessThanOrEqualTo(root.get("createdAt"), filter.toTime()));
        }
        return predicates;
    }

    private List<Order> resolveOrders(CriteriaBuilder cb, Root<InventoryLedger> root, Pageable pageable) {
        List<Order> orders = new ArrayList<>();
        if (pageable.getSort().isSorted()) {
            for (Sort.Order sortOrder : pageable.getSort()) {
                String prop = sortOrder.getProperty();
                if ("createdAt".equals(prop) || "movementType".equals(prop) || "quantity".equals(prop)) {
                    orders.add(sortOrder.isAscending() ? cb.asc(root.get(prop)) : cb.desc(root.get(prop)));
                }
            }
        }
        if (orders.isEmpty()) {
            orders.add(cb.desc(root.get("createdAt")));
        }
        return orders;
    }
}
