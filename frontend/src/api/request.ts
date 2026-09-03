import axios, { AxiosInstance, AxiosRequestConfig } from 'axios';

/** 后端错误响应结构（ApiExceptionHandler） */
interface ErrorBody {
  code?: number;
  message?: string;
}

const baseURL = import.meta.env.PROD ? '' : 'http://localhost:8020';

const instance: AxiosInstance = axios.create({
  baseURL,
  timeout: 60000,
});

instance.interceptors.response.use(
  (response) => response,
  (error) => {
    if (error.response) {
      const data = error.response.data as ErrorBody;
      if (data && typeof data === 'object' && data.message) {
        return Promise.reject(new Error(data.message));
      }
      return Promise.reject(new Error(`请求失败（${error.response.status}）`));
    }
    const config = error.config;
    const isUpload = config && (
      config.url?.includes('/runs') ||
      config.headers?.['Content-Type']?.toString().includes('multipart')
    );
    if (isUpload) {
      return Promise.reject(new Error('上传失败，可能是网络超时或文件过大，请重试'));
    }
    return Promise.reject(new Error('网络连接失败，请检查网络'));
  },
);

export const request = {
  get<T>(url: string, config?: AxiosRequestConfig): Promise<T> {
    return instance.get(url, config).then((res) => res.data);
  },

  post<T>(url: string, data?: unknown, config?: AxiosRequestConfig): Promise<T> {
    return instance.post(url, data, config).then((res) => res.data);
  },

  async upload<T>(url: string, formData: FormData, config?: AxiosRequestConfig): Promise<T> {
    const res = await instance.post(url, formData, {
      timeout: 120000,
      // 不手动设置 multipart Content-Type，让浏览器/Axios 自动补 boundary
      ...config,
    });
    return res.data;
  },
};

export function getErrorMessage(error: unknown): string {
  return error instanceof Error ? error.message : '未知错误';
}

export default request;
