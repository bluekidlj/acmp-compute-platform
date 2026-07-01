import { USE_MOCK, callApi, apiClient } from './client';
import type { PoolCard, PoolCardListResponse, PoolCardRequest } from '../types';
import { mockPoolCards, mockPools } from '../mock/data';

export const cardsApi = {
  listByPool: async (poolId: string): Promise<PoolCardListResponse> => {
    if (USE_MOCK) {
      const cards = mockPoolCards.filter((c) => c.poolId === poolId && c.status === 'active');
      const total = cards.reduce((s, c) => s + c.slots, 0);
      const bySpec: Record<string, { cards: number; slots: number }> = {};
      cards.forEach((c) => {
        if (!bySpec[c.specId]) bySpec[c.specId] = { cards: 0, slots: 0 };
        bySpec[c.specId].cards += 1;
        bySpec[c.specId].slots += c.slots;
      });
      return { poolId, totalNodes: total, cards, bySpec };
    }
    return callApi(() => apiClient.get<PoolCardListResponse>(`/pools/${poolId}/cards`));
  },
  add: async (poolId: string, req: PoolCardRequest): Promise<PoolCard> => {
    if (USE_MOCK) {
      const id = 'pcard-' + Date.now();
      // 计算 slots：A100=81920/DCU=16384/HUAWEI=65536 切 spec.defaultGpumemMb
      const cardMem: Record<string, number> = { NVIDIA: 81920, HYGON: 16384, HUAWEI_ASCEND: 65536 };
      const gpumem = (req.gpuModel.toLowerCase().includes('a100') || req.gpuModel.toLowerCase().includes('h100')) ? 81920
                    : req.gpuBrand === 'HYGON' ? 16384
                    : req.gpuBrand === 'HUAWEI_ASCEND' ? 65536 : 16384;
      const slots = 1; // 简化为 1：演示用
      const card: PoolCard = {
        id, poolId,
        gpuBrand: req.gpuBrand, gpuModel: req.gpuModel,
        nodeName: req.nodeName, serialNo: req.serialNo ?? null,
        specId: req.specId, slots,
        status: 'active',
        createdAt: new Date().toISOString(),
        updatedAt: new Date().toISOString(),
      };
      mockPoolCards.push(card);
      // 累加池 totalNodes
      const pool = mockPools.find((p) => p.id === poolId);
      if (pool) {
        pool.totalNodes = mockPoolCards
          .filter((c) => c.poolId === poolId && c.status === 'active')
          .reduce((s, c) => s + c.slots, 0);
        pool.updatedAt = new Date().toISOString();
      }
      return card;
    }
    return callApi(() => apiClient.post<PoolCard>(`/pools/${poolId}/cards`, req));
  },
  remove: async (poolId: string, cardId: string, force = false): Promise<void> => {
    if (USE_MOCK) {
      const idx = mockPoolCards.findIndex((c) => c.id === cardId);
      if (idx >= 0) mockPoolCards.splice(idx, 1);
      const pool = mockPools.find((p) => p.id === poolId);
      if (pool) {
        pool.totalNodes = mockPoolCards
          .filter((c) => c.poolId === poolId && c.status === 'active')
          .reduce((s, c) => s + c.slots, 0);
        pool.updatedAt = new Date().toISOString();
      }
      return;
    }
    return callApi(() => apiClient.delete<void>(`/pools/${poolId}/cards/${cardId}`, { params: { force } }));
  },
};