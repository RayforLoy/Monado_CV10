#!/usr/bin/env python3
"""Compare the production distortion-map lookup with separated and packed Vulkan images."""

import argparse
from pathlib import Path
import struct
import subprocess
from tempfile import TemporaryDirectory

import vulkan as vk


DIMENSION = 128
EYE_PIXELS = 1600
TRANSFER_DST_FEATURE = 0x8000  # VK_FORMAT_FEATURE_TRANSFER_DST_BIT, Vulkan 1.1.
SOURCE = Path(__file__).resolve().parents[1] / "src/xrt/auxiliary/render/shaders/distortion.comp"


def shader(glslang):
    original = SOURCE.read_text(encoding="utf-8")
    start = original.index("\tvec4 red_green =")
    end = original.index("\n\n", start)
    sample = original[start:end]
    glsl = """
#version 460
const uint k_view_count = 4;
layout(local_size_x=8, local_size_y=8, local_size_z=1) in;
layout(binding=0) uniform sampler2D distortion[12];
layout(binding=1, std430) buffer Results { uint count; uint maxError; } results;
layout(push_constant) uniform Config { vec4 distortion_options; } ubo;
void main() {
    uint eye = gl_GlobalInvocationID.z;
    uint iz = eye + (ubo.distortion_options.w > 0.0 ? 2 : 0);
    vec2 dist_uv = (vec2(gl_GlobalInvocationID.xy) + vec2(0.267, 0.751)) / 1600.0 * 1.025 - 0.012;
""" + sample + """
    vec2 e0 = abs(r_uv - texture(distortion[eye], dist_uv).xy);
    vec2 e1 = abs(g_uv - texture(distortion[4 + eye], dist_uv).xy);
    vec2 e2 = abs(b_uv - texture(distortion[8 + eye], dist_uv).xy);
    vec2 e = max(max(e0, e1), e2);
    if (any(greaterThan(e, vec2(0)))) {
        atomicAdd(results.count, 1);
        atomicMax(results.maxError, floatBitsToUint(max(e.x, e.y)));
    }
}
"""
    with TemporaryDirectory(prefix="cv10-packed-distortion-") as tmp:
        source, binary = Path(tmp) / "verify.comp", Path(tmp) / "verify.spv"
        source.write_text(glsl, encoding="utf-8")
        subprocess.run([glslang, "-V", "--target-env", "vulkan1.1",
                        "-o", str(binary), str(source)], check=True, stdout=subprocess.DEVNULL)
        return binary.read_bytes()


def texel_data(eye):
    red, green, blue, packed = (bytearray() for _ in range(4))
    for row in range(DIMENSION):
        y = row / (DIMENSION - 1) - 0.5
        for col in range(DIMENSION):
            x = col / (DIMENSION - 1) - 0.5
            radial = x * x + y * y
            planes = []
            for channel, slope in enumerate((0.37, 0.35, 0.33)):
                h = 0.5 + x * (1 + slope * radial) + 0.04 * eye + 0.001 * channel
                v = 0.5 + y * (1 + slope * radial) - 0.008 * eye
                planes.append(struct.pack("<ff", h, v))
            red.extend(planes[0])
            green.extend(planes[1])
            blue.extend(planes[2])
            packed.extend(planes[0] + planes[1])
    return red, green, blue, packed


def main(gpu, glslang):
    instance = vk.vkCreateInstance(vk.VkInstanceCreateInfo(pApplicationInfo=vk.VkApplicationInfo(
        pApplicationName="CV10 packed distortion differential", apiVersion=vk.VK_MAKE_VERSION(1, 1, 0))), None)
    physical = vk.vkEnumeratePhysicalDevices(instance)[gpu]
    print(vk.vkGetPhysicalDeviceProperties(physical).deviceName, flush=True)
    fmt2, fmt4 = vk.VK_FORMAT_R32G32_SFLOAT, vk.VK_FORMAT_R32G32B32A32_SFLOAT
    for fmt in (fmt2, fmt4):
        props = vk.vkGetPhysicalDeviceFormatProperties(physical, fmt)
        required = (vk.VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT |
                    vk.VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT | TRANSFER_DST_FEATURE)
        assert (props.optimalTilingFeatures & required) == required, props.optimalTilingFeatures
    family = next(i for i, q in enumerate(vk.vkGetPhysicalDeviceQueueFamilyProperties(physical))
                  if q.queueFlags & vk.VK_QUEUE_COMPUTE_BIT)
    device = vk.vkCreateDevice(physical, vk.VkDeviceCreateInfo(queueCreateInfoCount=1, pQueueCreateInfos=[
        vk.VkDeviceQueueCreateInfo(queueFamilyIndex=family, queueCount=1, pQueuePriorities=[1.0])],
        pEnabledFeatures=vk.VkPhysicalDeviceFeatures(shaderSampledImageArrayDynamicIndexing=True)), None)
    queue = vk.vkGetDeviceQueue(device, family, 0)
    cmd_pool = vk.vkCreateCommandPool(device, vk.VkCommandPoolCreateInfo(queueFamilyIndex=family), None)
    cmd = vk.vkAllocateCommandBuffers(device, vk.VkCommandBufferAllocateInfo(commandPool=cmd_pool,
                                      level=vk.VK_COMMAND_BUFFER_LEVEL_PRIMARY, commandBufferCount=3))
    mem = vk.vkGetPhysicalDeviceMemoryProperties(physical)
    buffers, images = [], []

    def allocate(req, mask):
        idx = next(i for i in range(mem.memoryTypeCount) if req.memoryTypeBits & (1 << i)
                   and mem.memoryTypes[i].propertyFlags & mask == mask)
        return vk.vkAllocateMemory(device, vk.VkMemoryAllocateInfo(allocationSize=req.size, memoryTypeIndex=idx), None)

    def buffer(size, usage):
        buf = vk.vkCreateBuffer(device, vk.VkBufferCreateInfo(size=size, usage=usage,
                                sharingMode=vk.VK_SHARING_MODE_EXCLUSIVE), None)
        alloc = allocate(vk.vkGetBufferMemoryRequirements(device, buf),
                         vk.VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | vk.VK_MEMORY_PROPERTY_HOST_COHERENT_BIT)
        vk.vkBindBufferMemory(device, buf, alloc, 0)
        buffers.append((buf, alloc))
        return buf, alloc

    def submit(index):
        vk.vkEndCommandBuffer(cmd[index])
        vk.vkQueueSubmit(queue, 1, [vk.VkSubmitInfo(commandBufferCount=1, pCommandBuffers=[cmd[index]])], None)
        vk.vkQueueWaitIdle(queue)

    def texture(raw, fmt):
        extent = vk.VkExtent3D(width=DIMENSION, height=DIMENSION, depth=1)
        image = vk.vkCreateImage(device, vk.VkImageCreateInfo(imageType=vk.VK_IMAGE_TYPE_2D,
                                 format=fmt, extent=extent, mipLevels=1, arrayLayers=1,
                                 samples=vk.VK_SAMPLE_COUNT_1_BIT, tiling=vk.VK_IMAGE_TILING_OPTIMAL,
                                 usage=vk.VK_IMAGE_USAGE_TRANSFER_DST_BIT | vk.VK_IMAGE_USAGE_SAMPLED_BIT,
                                 initialLayout=vk.VK_IMAGE_LAYOUT_UNDEFINED), None)
        alloc = allocate(vk.vkGetImageMemoryRequirements(device, image), vk.VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT)
        vk.vkBindImageMemory(device, image, alloc, 0)
        subrange = vk.VkImageSubresourceRange(aspectMask=vk.VK_IMAGE_ASPECT_COLOR_BIT, levelCount=1, layerCount=1)
        view = vk.vkCreateImageView(device, vk.VkImageViewCreateInfo(image=image, viewType=vk.VK_IMAGE_VIEW_TYPE_2D,
                                    format=fmt, subresourceRange=subrange), None)
        images.append((view, image, alloc))
        src, src_alloc = buffer(len(raw), vk.VK_BUFFER_USAGE_TRANSFER_SRC_BIT)
        mapped = vk.vkMapMemory(device, src_alloc, 0, len(raw), 0)
        mapped[:] = bytes(raw)
        vk.vkUnmapMemory(device, src_alloc)
        vk.vkCmdPipelineBarrier(cmd[0], vk.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, vk.VK_PIPELINE_STAGE_TRANSFER_BIT,
                                0, 0, None, 0, None, 1, [
            vk.VkImageMemoryBarrier(dstAccessMask=vk.VK_ACCESS_TRANSFER_WRITE_BIT,
                                    oldLayout=vk.VK_IMAGE_LAYOUT_UNDEFINED, newLayout=vk.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                                    srcQueueFamilyIndex=vk.VK_QUEUE_FAMILY_IGNORED, dstQueueFamilyIndex=vk.VK_QUEUE_FAMILY_IGNORED,
                                    image=image, subresourceRange=subrange)])
        layers = vk.VkImageSubresourceLayers(aspectMask=vk.VK_IMAGE_ASPECT_COLOR_BIT, layerCount=1)
        vk.vkCmdCopyBufferToImage(cmd[0], src, image, vk.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1,
                                  [vk.VkBufferImageCopy(imageSubresource=layers, imageExtent=extent)])
        vk.vkCmdPipelineBarrier(cmd[0], vk.VK_PIPELINE_STAGE_TRANSFER_BIT, vk.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                                0, 0, None, 0, None, 1, [
            vk.VkImageMemoryBarrier(srcAccessMask=vk.VK_ACCESS_TRANSFER_WRITE_BIT, dstAccessMask=vk.VK_ACCESS_SHADER_READ_BIT,
                                    oldLayout=vk.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, newLayout=vk.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                                    srcQueueFamilyIndex=vk.VK_QUEUE_FAMILY_IGNORED, dstQueueFamilyIndex=vk.VK_QUEUE_FAMILY_IGNORED,
                                    image=image, subresourceRange=subrange)])
        return view

    vk.vkBeginCommandBuffer(cmd[0], vk.VkCommandBufferBeginInfo())
    left, right = [tuple(texture(raw, fmt) for raw, fmt in zip(texel_data(eye), (fmt2, fmt2, fmt2, fmt4), strict=True))
                   for eye in (-1, 1)]
    submit(0)
    sampler = vk.vkCreateSampler(device, vk.VkSamplerCreateInfo(
        magFilter=vk.VK_FILTER_LINEAR, minFilter=vk.VK_FILTER_LINEAR, mipmapMode=vk.VK_SAMPLER_MIPMAP_MODE_NEAREST,
        addressModeU=vk.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE, addressModeV=vk.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE,
        addressModeW=vk.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE), None)
    stage = vk.VK_SHADER_STAGE_COMPUTE_BIT
    sampled = vk.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER
    storage = vk.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER
    layout = vk.vkCreateDescriptorSetLayout(device, vk.VkDescriptorSetLayoutCreateInfo(bindingCount=2, pBindings=[
        vk.VkDescriptorSetLayoutBinding(binding=0, descriptorType=sampled, descriptorCount=12, stageFlags=stage),
        vk.VkDescriptorSetLayoutBinding(binding=1, descriptorType=storage, descriptorCount=1, stageFlags=stage)]), None)
    pipe_layout = vk.vkCreatePipelineLayout(device, vk.VkPipelineLayoutCreateInfo(
        setLayoutCount=1, pSetLayouts=[layout], pushConstantRangeCount=1,
        pPushConstantRanges=[vk.VkPushConstantRange(stageFlags=stage, offset=0, size=16)]), None)
    shader_data = shader(glslang)
    module = vk.vkCreateShaderModule(device, vk.VkShaderModuleCreateInfo(codeSize=len(shader_data), pCode=shader_data), None)
    shader_stage = vk.VkPipelineShaderStageCreateInfo(stage=stage, module=module, pName="main")
    pipeline = vk.vkCreateComputePipelines(device, None, 1, [
        vk.VkComputePipelineCreateInfo(stage=shader_stage, layout=pipe_layout)], None)[0]
    pool = vk.vkCreateDescriptorPool(device, vk.VkDescriptorPoolCreateInfo(maxSets=1, poolSizeCount=2, pPoolSizes=[
        vk.VkDescriptorPoolSize(type=sampled, descriptorCount=12),
        vk.VkDescriptorPoolSize(type=storage, descriptorCount=1)]), None)
    desc_set = vk.vkAllocateDescriptorSets(device, vk.VkDescriptorSetAllocateInfo(descriptorPool=pool,
                                          descriptorSetCount=1, pSetLayouts=[layout]))[0]
    views = (left[0], right[0], left[3], right[3], left[1], right[1], left[2], right[2],
             left[2], right[2], left[2], right[2])
    image_infos = [vk.VkDescriptorImageInfo(sampler=sampler, imageView=view, imageLayout=vk.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
                   for view in views]
    output, output_mem = buffer(8, vk.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT)
    vk.vkUpdateDescriptorSets(device, 2, [
        vk.VkWriteDescriptorSet(dstSet=desc_set, dstBinding=0, descriptorCount=12, descriptorType=sampled, pImageInfo=image_infos),
        vk.VkWriteDescriptorSet(dstSet=desc_set, dstBinding=1, descriptorCount=1, descriptorType=storage,
                                pBufferInfo=[vk.VkDescriptorBufferInfo(buffer=output, offset=0, range=8)])], 0, None)
    mapped = vk.vkMapMemory(device, output_mem, 0, 8, 0)
    for packed in (0, 1):
        mapped[:] = bytes(8)
        index = packed + 1
        vk.vkBeginCommandBuffer(cmd[index], vk.VkCommandBufferBeginInfo())
        vk.vkCmdBindPipeline(cmd[index], vk.VK_PIPELINE_BIND_POINT_COMPUTE, pipeline)
        vk.vkCmdBindDescriptorSets(cmd[index], vk.VK_PIPELINE_BIND_POINT_COMPUTE, pipe_layout, 0, 1, [desc_set], 0, None)
        config = vk.ffi.new("float[4]", [0.0, 0.0, 0.0, float(packed)])
        vk.vkCmdPushConstants(cmd[index], pipe_layout, stage, 0, 16, config)
        vk.vkCmdDispatch(cmd[index], EYE_PIXELS // 8, EYE_PIXELS // 8, 2)
        vk.vkCmdPipelineBarrier(cmd[index], vk.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, vk.VK_PIPELINE_STAGE_HOST_BIT, 0, 1,
                                [vk.VkMemoryBarrier(srcAccessMask=vk.VK_ACCESS_SHADER_WRITE_BIT,
                                                     dstAccessMask=vk.VK_ACCESS_HOST_READ_BIT)], 0, None, 0, None)
        submit(index)
        count, max_error = struct.unpack("<If", bytes(mapped))
        print(f"packed={packed} pixels={2 * EYE_PIXELS ** 2} differences={count} max_uv_error={max_error}")
        assert count == 0 and max_error == 0.0
    vk.vkUnmapMemory(device, output_mem)
    vk.vkDestroyDescriptorPool(device, pool, None)
    vk.vkDestroyPipeline(device, pipeline, None)
    vk.vkDestroyShaderModule(device, module, None)
    vk.vkDestroyPipelineLayout(device, pipe_layout, None)
    vk.vkDestroyDescriptorSetLayout(device, layout, None)
    vk.vkDestroyCommandPool(device, cmd_pool, None)
    vk.vkDestroySampler(device, sampler, None)
    for view, image, alloc in images:
        vk.vkDestroyImageView(device, view, None)
        vk.vkDestroyImage(device, image, None)
        vk.vkFreeMemory(device, alloc, None)
    for buf, alloc in buffers:
        vk.vkDestroyBuffer(device, buf, None)
        vk.vkFreeMemory(device, alloc, None)
    vk.vkDestroyDevice(device, None)
    vk.vkDestroyInstance(instance, None)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--gpu", type=int, default=0, help="Vulkan physical device index")
    parser.add_argument("--glslang", default="glslangValidator", help="Path to glslangValidator")
    options = parser.parse_args()
    main(options.gpu, options.glslang)
