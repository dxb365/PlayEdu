/*
 * Copyright (C) 2023 杭州白书科技有限公司
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package xyz.playedu.api.controller.backend;

import java.util.*;
import java.util.stream.Collectors;
import lombok.SneakyThrows;
import org.apache.commons.collections4.MapUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import xyz.playedu.api.request.backend.ResourceDestroyMultiRequest;
import xyz.playedu.api.request.backend.ResourceBulkRequest;
import xyz.playedu.api.service.ResourceBulkService;
import xyz.playedu.api.request.backend.ResourceUpdateRequest;
import xyz.playedu.common.annotation.Log;
import xyz.playedu.common.bus.BackendBus;
import xyz.playedu.common.constant.BackendConstant;
import xyz.playedu.common.constant.BusinessTypeConstant;
import xyz.playedu.common.context.BCtx;
import xyz.playedu.common.domain.AdminUser;
import xyz.playedu.common.domain.Category;
import xyz.playedu.common.exception.NotFoundException;
import xyz.playedu.common.exception.ServiceException;
import xyz.playedu.common.service.AdminUserService;
import xyz.playedu.common.service.CategoryService;
import xyz.playedu.common.types.JsonResponse;
import xyz.playedu.common.types.paginate.PaginationResult;
import xyz.playedu.common.types.paginate.ResourcePaginateFilter;
import xyz.playedu.common.util.StringUtil;
import xyz.playedu.resource.domain.Resource;
import xyz.playedu.resource.domain.ResourceExtra;
import xyz.playedu.resource.service.ResourceExtraService;
import xyz.playedu.resource.service.ResourceService;

@RestController
@RequestMapping("/backend/v1/resource")
public class ResourceController {

    @Autowired private AdminUserService adminUserService;

    @Autowired private ResourceService resourceService;

    @Autowired private ResourceExtraService resourceExtraService;

    @Autowired private BackendBus backendBus;

    @Autowired private CategoryService categoryService;

    @Autowired private ResourceBulkService bulkService;

    @GetMapping("/index")
    @Log(title = "资源-列表", businessType = BusinessTypeConstant.GET)
    public JsonResponse index(@RequestParam HashMap<String, Object> params) {
        Integer page = MapUtils.getInteger(params, "page", 1);
        Integer size = MapUtils.getInteger(params, "size", 10);
        String sortField = MapUtils.getString(params, "sort_field");
        String sortAlgo = MapUtils.getString(params, "sort_algo");
        String name = MapUtils.getString(params, "name");
        String type = MapUtils.getString(params, "type");
        String categoryIds = MapUtils.getString(params, "category_ids");

        if (type == null || type.trim().isEmpty()) {
            return JsonResponse.error("请选择资源类型");
        }

        // 获取所有子类
        Set<Integer> allCategoryIdsSet = new HashSet<>();
        if (StringUtil.isNotEmpty(categoryIds)) {
            String[] categoryIdArr = categoryIds.split(",");
            if (StringUtil.isNotEmpty(categoryIdArr)) {
                for (String categoryIdStr : categoryIdArr) {
                    Integer categoryId = Integer.parseInt(categoryIdStr);
                    allCategoryIdsSet.add(categoryId);
                    // 查询所有的子分类
                    List<Category> categoryList =
                            categoryService.getChildCategorysByParentId(categoryId);
                    if (StringUtil.isNotEmpty(categoryList)) {
                        for (Category category : categoryList) {
                            allCategoryIdsSet.add(category.getId());
                        }
                    }
                }
            }
        }

        List<Integer> allCategoryIds = new ArrayList<>();
        if ("0".equals(categoryIds)) {
            allCategoryIds.add(0);
        }
        if (StringUtil.isNotEmpty(allCategoryIdsSet)) {
            allCategoryIds.addAll(allCategoryIdsSet);
        }

        ResourcePaginateFilter filter = new ResourcePaginateFilter();
        filter.setSortAlgo(sortAlgo);
        filter.setSortField(sortField);
        filter.setType(type);
        filter.setCategoryIds(allCategoryIds);
        filter.setName(name);

        if (!backendBus.isSuperAdmin()) { // 非超管只能读取它自己上传的资源
            filter.setAdminId(BCtx.getId());
        }

        PaginationResult<Resource> result = resourceService.paginate(page, size, filter);

        HashMap<String, Object> data = new HashMap<>();
        data.put("result", result);

        List<Integer> ids = result.getData().stream().map(Resource::getId).toList();
        if (StringUtil.isNotEmpty(ids)) {
            if (type.equals(BackendConstant.RESOURCE_TYPE_VIDEO)) {
                List<ResourceExtra> resourceExtras = resourceExtraService.chunksByRids(ids);
                Map<Integer, ResourceExtra> resourceVideosExtra =
                        resourceExtras.stream()
                                .collect(Collectors.toMap(ResourceExtra::getRid, e -> e));
                data.put("videos_extra", resourceVideosExtra);
            }

            // 获取资源签名url
            data.put("resource_url", resourceService.chunksPreSignUrlByIds(ids));
        }

        // 操作人
        data.put("admin_users", new HashMap<>());
        if (!result.getData().isEmpty()) {
            Map<Integer, String> adminUsers =
                    adminUserService
                            .chunks(result.getData().stream().map(Resource::getAdminId).toList())
                            .stream()
                            .collect(Collectors.toMap(AdminUser::getId, AdminUser::getName));
            data.put("admin_users", adminUsers);
        }

        if (!type.equals(BackendConstant.RESOURCE_TYPE_VIDEO)
                && !type.equals(BackendConstant.RESOURCE_TYPE_IMAGE)) {
            filter.setType(BackendConstant.RESOURCE_TYPE_ATTACHMENT);
            data.put("existing_types", resourceService.paginateType(filter));
        }
        return JsonResponse.data(data);
    }

    @PostMapping("/bulk")
    @Log(title = "视频-批量操作", businessType = BusinessTypeConstant.UPDATE)
    public JsonResponse bulk(@RequestBody @Validated ResourceBulkRequest request) {
        return JsonResponse.data(bulkService.perform(request));
    }

    @DeleteMapping("/{id}")
    @Log(title = "资源-删除", businessType = BusinessTypeConstant.DELETE)
    public JsonResponse destroy(@PathVariable(name = "id") Integer id) throws NotFoundException {
        resourceService.findOrFail(id);
        bulkService.delete(List.of(id));
        return JsonResponse.success();
    }

    @PostMapping("/destroy-multi")
    @Log(title = "资源-批量删除", businessType = BusinessTypeConstant.DELETE)
    public JsonResponse multiDestroy(@RequestBody @Validated ResourceDestroyMultiRequest req) {
        bulkService.delete(req.getIds());
        return JsonResponse.success();
    }

    @GetMapping("/{id}")
    @SneakyThrows
    @Log(title = "资源-编辑", businessType = BusinessTypeConstant.GET)
    public JsonResponse edit(@PathVariable(name = "id") Integer id) {
        Resource resource = resourceService.findOrFail(id);

        if (!backendBus.isSuperAdmin()) {
            if (!resource.getAdminId().equals(BCtx.getId())) {
                throw new ServiceException("无权限");
            }
        }

        HashMap<String, Object> data = new HashMap<>();
        data.put("resources", resource);
        data.put("category_ids", resourceService.categoryIds(id));
        // 获取资源签名url
        data.put(
                "resource_url",
                resourceService.chunksPreSignUrlByIds(
                        new ArrayList<>() {
                            {
                                add(id);
                            }
                        }));
        return JsonResponse.data(data);
    }

    @PutMapping("/{id}")
    @SneakyThrows
    @Log(title = "资源-编辑", businessType = BusinessTypeConstant.UPDATE)
    public JsonResponse update(
            @RequestBody @Validated ResourceUpdateRequest req,
            @PathVariable(name = "id") Integer id) {
        Resource resource = resourceService.findOrFail(id);

        if (!backendBus.isSuperAdmin()) {
            if (!resource.getAdminId().equals(BCtx.getId())) {
                throw new ServiceException("无权限");
            }
        }

        resourceService.updateNameAndCategoryId(
                resource.getId(), req.getName(), req.getCategoryId());
        return JsonResponse.success();
    }
}
