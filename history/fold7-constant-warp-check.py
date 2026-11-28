#!/usr/bin/env python3
"""Compare the fused scanout transform against the baseline on Windows Vulkan."""

import argparse
import ctypes as ct
import math
from pathlib import Path
import struct
import subprocess
from tempfile import TemporaryDirectory

import vulkan as vk


REPO = Path(__file__).resolve().parents[1]
SHADER = REPO / 'src/xrt/auxiliary/render/shaders/distortion.comp'
COMPUTE = REPO / 'src/xrt/auxiliary/render/render_compute.c'
MATRIX = ct.c_float * 16
RECT = ct.c_float * 4
POLYNOMIAL = RECT * 5


def build_coefficients(directory, compiler):
    compute = COMPUTE.read_text(encoding='utf-8')
    start = compute.index('\ncalc_scanout_warp_polynomial(') + 1
    end = compute.index('\n}\n', start) + len('\n}')
    source = directory / 'coefficients.c'
    source.write_text('''#include <stdint.h>
struct xrt_normalized_rect { float x, y, w, h; };
struct xrt_matrix_4x4 { float v[16]; };
__declspec(dllexport) void
''' + compute[start:end], encoding='utf-8')
    library = directory / 'coefficients.dll'
    subprocess.run([compiler, '/nologo', '/O2', '/LD', '/Fe:coefficients.dll', source.name],
                   cwd=directory, check=True)
    return library


def build_shader(directory, glslang):
    source = SHADER.read_text(encoding='utf-8').split('void main()', 1)[0] + '''
layout(std430, binding=4) buffer Results { uint values[5]; } results;
void main() {
    uint eye = gl_GlobalInvocationID.z;
    vec2 uv = (vec2(gl_GlobalInvocationID.xy) + vec2(0.317, 0.581)) / 800.0 * 1.08 - 0.04;
    vec2 base = transform_uv_timewarp(uv, eye);
    vec2 candidate = transform_uv_timewarp_polynomial(uv, eye, ubo.distortion_options.z == 1.0);
    vec2 error = abs(base - candidate);
    float clip_w = dot(vec4(1, uv, uv.x * uv.y), vec4(ubo.scanout_warp[eye][0].z, ubo.scanout_warp[eye][1].z,
                       ubo.scanout_warp[eye][2].z, ubo.scanout_warp[eye][3].z)) + uv.y * uv.y * ubo.scanout_warp[eye][4].z;
    if (clip_w < 0.12499) {
        atomicAdd(results.values[2], 1);
        if (any(greaterThan(error, vec2(0)))) atomicAdd(results.values[3], 1);
    }
    if (inside_view(base) || inside_view(candidate)) {
        atomicMax(results.values[0], floatBitsToUint(max(error.x, error.y)));
        atomicAdd(results.values[1], 1);
        if (inside_view(base) != inside_view(candidate)) atomicAdd(results.values[4], 1);
    }
}
'''
    fixture = directory / 'coords.comp'
    binary = directory / 'coords.spv'
    fixture.write_text(source, encoding='utf-8')
    subprocess.run([glslang, '-V', '--target-env', 'vulkan1.1',
                    f'-I{SHADER.parent}', '-o', str(binary), str(fixture)], check=True)
    return binary.read_bytes()


def matmul(a, b):
    return [sum(a[4*k+i] * b[4*j+k] for k in range(4)) for j in range(4) for i in range(4)]


def rotation(yaw, pitch, roll):
    cy, cx, cz = [math.cos(math.radians(x)) for x in (yaw, pitch, roll)]
    sy, sx, sz = [math.sin(math.radians(x)) for x in (yaw, pitch, roll)]
    y = [cy,0,-sy,0, 0,1,0,0, sy,0,cy,0, 0,0,0,1]
    x = [1,0,0,0, 0,cx,sx,0, 0,-sx,cx,0, 0,0,0,1]
    z = [cz,sz,0,0, -sz,cz,0,0, 0,0,1,0, 0,0,0,1]
    return matmul(y, matmul(x, z))


def config(calc, begin, end):
    rects, matrices_begin, matrices_end, coefficients = [], [], [], []
    for eye in (-1, 1):
        rect = RECT(-0.65 + eye*0.08, -0.66, 1.31, 1.33)
        proj = [1.60,0,0,0, 0,1.59,0,0, eye*0.045,-0.035,-1.001,-1, 0,0,-0.02,0]
        first = MATRIX(*matmul(proj, rotation(*begin)))
        last = MATRIX(*matmul(proj, rotation(*end)))
        poly = POLYNOMIAL()
        calc(ct.byref(rect), ct.byref(first), ct.byref(last), ct.byref(poly))
        rects.append(bytes(rect))
        matrices_begin.append(bytes(first))
        matrices_end.append(bytes(last))
        coefficients.append(bytes(poly))
    # Offsets correspond to the std140 Config layout in distortion.comp.
    return b'\0'*32 + b''.join(rects) + b'\0'*32 + b''.join(matrices_begin + matrices_end) + struct.pack('<4f', 0, 0, 2 if begin == end else 1, 0) + b'\0'*64 + b''.join(coefficients)


def compare(dll, shader_source, gpu):
    calc = dll.calc_scanout_warp_polynomial
    calc.argtypes = tuple(ct.POINTER(t) for t in (RECT, MATRIX, MATRIX, POLYNOMIAL))
    calc.restype = None
    instance = vk.vkCreateInstance(vk.VkInstanceCreateInfo(pApplicationInfo=vk.VkApplicationInfo(
        pApplicationName='CV10 coordinate comparison', apiVersion=vk.VK_MAKE_VERSION(1, 1, 0))), None)
    physical = vk.vkEnumeratePhysicalDevices(instance)[gpu]
    print(vk.vkGetPhysicalDeviceProperties(physical).deviceName)
    family = next(i for i, q in enumerate(vk.vkGetPhysicalDeviceQueueFamilyProperties(physical))
                  if q.queueFlags & vk.VK_QUEUE_COMPUTE_BIT)
    device = vk.vkCreateDevice(physical, vk.VkDeviceCreateInfo(queueCreateInfoCount=1, pQueueCreateInfos=[
        vk.VkDeviceQueueCreateInfo(queueFamilyIndex=family, queueCount=1, pQueuePriorities=[1.0])]), None)
    queue = vk.vkGetDeviceQueue(device, family, 0)
    const = vk.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER
    storage = vk.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER
    bindings = [(3, const, 592, vk.VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT),
                (4, storage, 20, vk.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT)]
    layout = vk.vkCreateDescriptorSetLayout(device, vk.VkDescriptorSetLayoutCreateInfo(bindingCount=2, pBindings=[
        vk.VkDescriptorSetLayoutBinding(binding=b, descriptorType=t, descriptorCount=1,
                                       stageFlags=vk.VK_SHADER_STAGE_COMPUTE_BIT) for b,t,_,_ in bindings]), None)
    pipeline_layout = vk.vkCreatePipelineLayout(device, vk.VkPipelineLayoutCreateInfo(setLayoutCount=1, pSetLayouts=[layout]), None)
    module = vk.vkCreateShaderModule(device, vk.VkShaderModuleCreateInfo(codeSize=len(shader_source), pCode=shader_source), None)
    stage = vk.VkPipelineShaderStageCreateInfo(stage=vk.VK_SHADER_STAGE_COMPUTE_BIT, module=module, pName='main')
    pipeline = vk.vkCreateComputePipelines(device, None, 1, [vk.VkComputePipelineCreateInfo(stage=stage, layout=pipeline_layout)], None)[0]
    pool = vk.vkCreateDescriptorPool(device, vk.VkDescriptorPoolCreateInfo(maxSets=1, poolSizeCount=2, pPoolSizes=[
        vk.VkDescriptorPoolSize(type=t, descriptorCount=1) for _,t,_,_ in bindings]), None)
    ds = vk.vkAllocateDescriptorSets(device, vk.VkDescriptorSetAllocateInfo(descriptorPool=pool, descriptorSetCount=1, pSetLayouts=[layout]))[0]
    writes, buffers, allocations, mapped = [], [], [], []
    memories = vk.vkGetPhysicalDeviceMemoryProperties(physical)
    mask = vk.VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | vk.VK_MEMORY_PROPERTY_HOST_COHERENT_BIT
    for b,t,size,usage in bindings:
        buffer = vk.vkCreateBuffer(device, vk.VkBufferCreateInfo(size=size, usage=usage, sharingMode=vk.VK_SHARING_MODE_EXCLUSIVE), None)
        req = vk.vkGetBufferMemoryRequirements(device, buffer)
        m = next(i for i in range(memories.memoryTypeCount) if req.memoryTypeBits & (1 << i)
                 and memories.memoryTypes[i].propertyFlags & mask == mask)
        memory = vk.vkAllocateMemory(device, vk.VkMemoryAllocateInfo(allocationSize=req.size, memoryTypeIndex=m), None)
        vk.vkBindBufferMemory(device, buffer, memory, 0)
        mapped.append(vk.vkMapMemory(device, memory, 0, size, 0))
        buffers.append(buffer)
        allocations.append(memory)
        writes.append(vk.VkWriteDescriptorSet(dstSet=ds, dstBinding=b, descriptorCount=1, descriptorType=t,
                                              pBufferInfo=[vk.VkDescriptorBufferInfo(buffer=buffer, offset=0, range=size)]))
    vk.vkUpdateDescriptorSets(device, 2, writes, 0, None)
    command_pool = vk.vkCreateCommandPool(device, vk.VkCommandPoolCreateInfo(queueFamilyIndex=family), None)
    cmd = vk.vkAllocateCommandBuffers(device, vk.VkCommandBufferAllocateInfo(commandPool=command_pool,
                                      level=vk.VK_COMMAND_BUFFER_LEVEL_PRIMARY, commandBufferCount=1))[0]
    vk.vkBeginCommandBuffer(cmd, vk.VkCommandBufferBeginInfo())
    vk.vkCmdBindPipeline(cmd, vk.VK_PIPELINE_BIND_POINT_COMPUTE, pipeline)
    vk.vkCmdBindDescriptorSets(cmd, vk.VK_PIPELINE_BIND_POINT_COMPUTE, pipeline_layout, 0, 1, [ds], 0, None)
    vk.vkCmdDispatch(cmd, 100, 100, 2)
    barrier = vk.VkMemoryBarrier(srcAccessMask=vk.VK_ACCESS_SHADER_WRITE_BIT, dstAccessMask=vk.VK_ACCESS_HOST_READ_BIT)
    vk.vkCmdPipelineBarrier(cmd, vk.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, vk.VK_PIPELINE_STAGE_HOST_BIT,
                           0, 1, [barrier], 0, None, 0, None)
    vk.vkEndCommandBuffer(cmd)
    cases = [((0,0,0),(0,0,0)), ((-17,0,0),(-16,0,0)), ((17,0,0),(18,0,0)),
             ((3,22,45),(4,25,50)), ((2,-25,-45),(0,-28,-50)), ((50,15,20),(55,18,21)),
             ((89,8,10),(91,8,9)), ((110,-7,-5),(116,-5,-4))]
    cases += [(begin, begin) for begin, end in cases[1:]]
    for begin,end in cases:
        mapped[0][:] = config(calc, begin, end)
        mapped[1][:] = bytes(20)
        vk.vkQueueSubmit(queue, 1, [vk.VkSubmitInfo(commandBufferCount=1, pCommandBuffers=[cmd])], None)
        vk.vkQueueWaitIdle(queue)
        result = struct.unpack('<fIIII', bytes(mapped[1]))
        print(f'{begin} -> {end}  UV error {result[0]:.9g}  visible {result[1]}  fallback {result[2]}  '
              f'fallback differences {result[3]}  visibility differences {result[4]}')
        assert result[0] <= 2e-6 and result[3] == 0 and result[4] < 6, result
    vk.vkDestroyCommandPool(device, command_pool, None)
    for buffer,memory in zip(buffers, allocations):
        vk.vkUnmapMemory(device, memory)
        vk.vkDestroyBuffer(device, buffer, None)
        vk.vkFreeMemory(device, memory, None)
    vk.vkDestroyDescriptorPool(device, pool, None)
    vk.vkDestroyPipeline(device, pipeline, None)
    vk.vkDestroyShaderModule(device, module, None)
    vk.vkDestroyPipelineLayout(device, pipeline_layout, None)
    vk.vkDestroyDescriptorSetLayout(device, layout, None)
    vk.vkDestroyDevice(device, None)
    vk.vkDestroyInstance(instance, None)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--glslang', required=True)
    parser.add_argument('--cc', default='cl', help='MSVC compiler from an x64 developer shell')
    parser.add_argument('--gpu', type=int, default=0, help='Vulkan GPU index')
    args = parser.parse_args()
    with TemporaryDirectory() as temporary:
        directory = Path(temporary)
        library = build_coefficients(directory, args.cc)
        shader_source = build_shader(directory, args.glslang)
        dll = ct.CDLL(str(library))
        try:
            compare(dll, shader_source, args.gpu)
        finally:
            # Windows keeps the temporary DLL locked until its loader reference is released.
            ct.windll.kernel32.FreeLibrary(ct.c_void_p(dll._handle))


if __name__ == '__main__':
    main()
