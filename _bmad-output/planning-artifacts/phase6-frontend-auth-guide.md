# Phase 6 — Frontend Auth Module (Theo cấu trúc Tourism Frontend)

> **Mục tiêu:** Refactor phần xác thực theo đúng cấu trúc hiện có của project `tourism_frontend/client-side`. Tích hợp Backend-mediated Google OAuth2 qua Keycloak, thay thế Google ID Token cũ bằng Authorization Code flow.

---

## Cấu trúc thư mục hiện tại của project

```
src/
├── utils/
│   └── axiosCustomize.js          ← Axios instance + interceptor (đã có)
├── context/
│   └── AuthContext.jsx             ← Auth state management (cần cập nhật)
├── services/
│   └── auth/
│       └── auth.ts                 ← (tạo mới) Gọi API auth
├── hook/
│   └── useGoogleLogin.js          ← (tạo mới) Hook Google OAuth2
├── components/
│   ├── Login/
│   │   ├── Login.jsx               ← (cập nhật) Thay GoogleLogin SDK → custom button
│   │   └── Login.module.scss       ← (không đổi)
│   ├── VerifyEmail/
│   │   └── VerifyEmail.jsx         ← (đã có)
│   ├── ProtectedRoute.jsx          ← (không đổi)
│   └── GoogleCallback/
│       └── GoogleCallback.jsx      ← (tạo mới) Xử lý callback OAuth2
└── App.tsx                         ← (cập nhật) Thêm route /auth/google/callback
```

---

## Thứ tự thực hiện

| Bước | File | Hành động | Lý do |
|------|------|-----------|-------|
| 1 | `utils/axiosCustomize.js` | Cập nhật nhỏ | Đồng bộ key localStorage |
| 2 | `services/auth/auth.ts` | Tạo mới | Tách API call ra khỏi Context |
| 3 | `context/AuthContext.jsx` | Cập nhật | Thêm googleLogin Backend-mediated |
| 4 | `hook/useGoogleLogin.js` | Tạo mới | Logic redirect sang Keycloak |
| 5 | `components/GoogleCallback/GoogleCallback.jsx` | Tạo mới | Nhận code từ Keycloak |
| 6 | `components/Login/Login.jsx` | Cập nhật | Thay SDK GoogleLogin → custom button |
| 7 | `App.tsx` | Cập nhật | Thêm route callback |
| 8 | `.env` | Cập nhật | Thêm biến Keycloak |

---

## Bước 1 — `src/utils/axiosCustomize.js` (cập nhật nhỏ)

> **Giữ nguyên** file hiện tại. Chỉ đảm bảo key localStorage khớp: `accessToken`, `refreshToken`, `user`.

File hiện tại đã đúng — không cần sửa gì thêm.

---

## Bước 2 — `src/services/auth/auth.ts` (tạo mới)

Tách API call ra khỏi Context để dễ tái sử dụng (giống pattern `services/tours/tours.ts` hiện có).

```typescript
import axiosInstance from '../../utils/axiosCustomize';

export interface LoginResponse {
  accessToken: string;
  refreshToken: string;
  user: {
    userId: number;
    fullName: string;
    email: string;
    avatar: string | null;
    role: 'CUSTOMER' | 'ADMIN' | 'TOUR_OWNER';
    provinceName: string | null;
    districtName: string | null;
    coinBalance: number;
  };
}

export const authAPI = {
  login: (email: string, password: string) =>
    axiosInstance.post<LoginResponse>('/auth/login', { email, password }),

  register: (data: {
    fullName: string;
    email: string;
    password: string;
    confirmPassword: string;
    provinceCode?: string;
    provinceName?: string;
    districtCode?: string;
    districtName?: string;
  }) => axiosInstance.post('/auth/register', data),

  verifyEmail: (token: string) =>
    axiosInstance.get('/auth/verify-email', { params: { token } }),

  resendVerification: (email: string) =>
    axiosInstance.post('/auth/resend-verification', null, { params: { email } }),

  refreshToken: (refreshToken: string) =>
    axiosInstance.post('/auth/refresh-token', { refreshToken }),

  logout: (refreshToken: string) =>
    axiosInstance.post('/auth/logout', { refreshToken }),

  logoutAll: (userId: number) =>
    axiosInstance.post('/auth/logout-all', { userId }),

  // Backend-mediated Google OAuth2 (Keycloak authorization_code flow)
  googleLogin: (code: string, redirectUri: string) =>
    axiosInstance.post<LoginResponse>('/auth/google-login', { code, redirectUri }),
};
```

---

## Bước 3 — `src/context/AuthContext.jsx` (cập nhật)

Thay toàn bộ nội dung file bằng code dưới đây. Điểm thay đổi chính:
- Bỏ `loginWithGoogle(idToken)` cũ (dùng Google ID Token SDK trực tiếp)
- Thêm `loginWithGoogleCode(code, redirectUri)` mới (Backend-mediated qua Keycloak)

```jsx
import React, { createContext, useState, useEffect, useContext } from 'react';
import axios from '../utils/axiosCustomize';

const AuthContext = createContext(null);

export const AuthProvider = ({ children }) => {
    const [user, setUser] = useState(null);
    const [loading, setLoading] = useState(true);
    const [isAuthenticated, setIsAuthenticated] = useState(false);

    useEffect(() => {
        checkAuth();
    }, []);

    const fetchProfile = async () => {
        try {
            const response = await axios.get('/auth/profile');
            if (response) {
                const userData = response.data;
                setUser(userData);
                localStorage.setItem('user', JSON.stringify(userData));
            }
        } catch (error) {
            console.error('Lỗi cập nhật thông tin user:', error);
            setUser(null);
            setIsAuthenticated(false);
        }
    };

    const checkAuth = async () => {
        // DEV ONLY: hardcode userId for testing without auth
        const devUserId = parseInt(process.env.REACT_APP_DEV_USER_ID || '0');
        if (devUserId) {
            try {
                const res = await axios.get(`/users/${devUserId}`);
                const data = res.data;
                const realUser = {
                    id: data.userID, userId: data.userID, userID: data.userID,
                    fullName: data.fullName || data.fullname || '',
                    email: data.email || '',
                    phone: data.phone || '',
                    dateOfBirth: data.dateOfBirth || null,
                    coinBalance: data.coinBalance || 0,
                    avatar: data.avatar || null,
                    status: data.status,
                    role: data.role || 'CUSTOMER',
                };
                setUser(realUser);
                setIsAuthenticated(true);
            } catch (e) {
                console.error('Dev mode: failed to fetch user', e);
                setUser({ id: devUserId, userId: devUserId, userID: devUserId, fullName: 'Dev User', email: 'dev@test.com', role: 'CUSTOMER' });
                setIsAuthenticated(true);
            }
            setLoading(false);
            return;
        }

        try {
            const token = localStorage.getItem('accessToken');
            const userStr = localStorage.getItem('user');

            if (token && userStr) {
                const userData = JSON.parse(userStr);
                setUser(userData);
                setIsAuthenticated(true);
                await fetchProfile();
            } else {
                setUser(null);
                setIsAuthenticated(false);
            }
        } catch (error) {
            console.error('Error checking auth:', error);
            logout();
        } finally {
            setLoading(false);
        }
    };

    const login = async (email, password) => {
        try {
            const response = await axios.post('/auth/login', { email, password });
            const { accessToken, refreshToken, user: userData } = response.data;

            localStorage.setItem('accessToken', accessToken);
            localStorage.setItem('refreshToken', refreshToken);
            localStorage.setItem('user', JSON.stringify(userData));

            setUser(userData);
            setIsAuthenticated(true);

            return { success: true, user: userData };
        } catch (error) {
            console.error('Login error:', error);
            throw error;
        }
    };

    // ─── Google Login mới: Backend-mediated qua Keycloak ───────────────────────
    // Nhận authorization code từ Keycloak callback, gửi lên IAM service
    const loginWithGoogleCode = async (code, redirectUri) => {
        try {
            const response = await axios.post('/auth/google-login', { code, redirectUri });
            const { accessToken, refreshToken, user: userData } = response.data;

            localStorage.setItem('accessToken', accessToken);
            localStorage.setItem('refreshToken', refreshToken);
            localStorage.setItem('user', JSON.stringify(userData));

            setUser(userData);
            setIsAuthenticated(true);

            return { success: true, user: userData };
        } catch (error) {
            console.error('Google login error:', error);
            throw error;
        }
    };

    const register = async (registerData) => {
        try {
            const response = await axios.post('/auth/register', registerData);
            return { success: true, data: response.data };
        } catch (error) {
            console.error('Register error:', error);
            return {
                success: false,
                message: error.response?.data?.message || 'Đăng ký thất bại'
            };
        }
    };

    const logout = async () => {
        try {
            const refreshToken = localStorage.getItem('refreshToken');
            if (refreshToken) {
                await axios.post('/auth/logout', { refreshToken });
            }
        } catch (error) {
            console.error('Logout error:', error);
        } finally {
            localStorage.removeItem('accessToken');
            localStorage.removeItem('refreshToken');
            localStorage.removeItem('user');
            setUser(null);
            setIsAuthenticated(false);
        }
    };

    const updateUser = (updatedUserData) => {
        const updatedUser = { ...user, ...updatedUserData };
        setUser(updatedUser);
        localStorage.setItem('user', JSON.stringify(updatedUser));
    };

    const refreshAccessToken = async () => {
        try {
            const refreshToken = localStorage.getItem('refreshToken');
            if (!refreshToken) throw new Error('No refresh token');

            const response = await axios.post('/auth/refresh-token', { refreshToken });
            const { accessToken, refreshToken: newRefreshToken } = response.data;

            localStorage.setItem('accessToken', accessToken);
            if (newRefreshToken) {
                localStorage.setItem('refreshToken', newRefreshToken);
            }

            return accessToken;
        } catch (error) {
            console.error('Refresh token error:', error);
            logout();
            throw error;
        }
    };

    const value = {
        user,
        loading,
        isAuthenticated,
        login,
        loginWithGoogleCode,   // ← thay loginWithGoogle cũ
        register,
        logout,
        updateUser,
        refreshAccessToken,
        checkAuth
    };

    return (
        <AuthContext.Provider value={value}>
            {children}
        </AuthContext.Provider>
    );
};

export const useAuth = () => {
    const context = useContext(AuthContext);
    if (!context) {
        throw new Error('useAuth must be used within AuthProvider');
    }
    return context;
};

export default AuthContext;
```

---

## Bước 4 — `src/hook/useGoogleLogin.ts` (tạo mới)

Đặt cùng thư mục với các hook khác: `hook/useAdminBookings.ts`, `hook/useUser.ts`, ...

```typescript
// src/hook/useGoogleLogin.ts

const KEYCLOAK_URL = process.env.REACT_APP_KEYCLOAK_URL || 'http://localhost:8180';
const KEYCLOAK_REALM = process.env.REACT_APP_KEYCLOAK_REALM || 'tourism';
const KEYCLOAK_CLIENT_ID = process.env.REACT_APP_KEYCLOAK_CLIENT_ID || 'tourism-app';
const GOOGLE_REDIRECT_URI = process.env.REACT_APP_GOOGLE_REDIRECT_URI || 'http://localhost:3000/auth/google/callback';

interface UseGoogleLoginReturn {
    initiateGoogleLogin: () => void;
    GOOGLE_REDIRECT_URI: string;
}

export const useGoogleLogin = (): UseGoogleLoginReturn => {
    // Redirect trình duyệt sang Keycloak, Keycloak tự redirect sang Google
    const initiateGoogleLogin = (): void => {
        const params = new URLSearchParams({
            client_id: KEYCLOAK_CLIENT_ID,
            redirect_uri: GOOGLE_REDIRECT_URI,
            response_type: 'code',
            scope: 'openid email profile',
            kc_idp_hint: 'google',   // Keycloak biết dùng Google Identity Provider
        });

        const authUrl = `${KEYCLOAK_URL}/realms/${KEYCLOAK_REALM}/protocol/openid-connect/auth?${params.toString()}`;
        window.location.href = authUrl;
    };

    return { initiateGoogleLogin, GOOGLE_REDIRECT_URI };
};
```

---

## Bước 5 — `src/components/GoogleCallback/GoogleCallback.jsx` (tạo mới)

Trang này được gọi khi Keycloak redirect về sau khi Google xác thực thành công.

```jsx
// src/components/GoogleCallback/GoogleCallback.jsx

import React, { useEffect, useRef } from 'react';
import { useNavigate } from 'react-router-dom';
import { useAuth } from '../../context/AuthContext';

const GOOGLE_REDIRECT_URI = process.env.REACT_APP_GOOGLE_REDIRECT_URI || 'http://localhost:3000/auth/google/callback';

const GoogleCallback = () => {
    const navigate = useNavigate();
    const { loginWithGoogleCode } = useAuth();
    const called = useRef(false); // tránh gọi 2 lần do React StrictMode

    useEffect(() => {
        if (called.current) return;
        called.current = true;

        const params = new URLSearchParams(window.location.search);
        const code = params.get('code');
        const error = params.get('error');

        if (error || !code) {
            console.error('Google callback error:', error);
            navigate('/login?error=google_failed', { replace: true });
            return;
        }

        loginWithGoogleCode(code, GOOGLE_REDIRECT_URI)
            .then((result) => {
                const userRole = result.user?.role;
                if (userRole === 'ADMIN') {
                    navigate('/admin/dashboard', { replace: true });
                } else {
                    window.location.replace('/');
                }
            })
            .catch((err) => {
                console.error('Google login failed:', err);
                navigate('/login?error=google_failed', { replace: true });
            });
    }, []);

    return (
        <div style={{
            minHeight: '100vh',
            display: 'flex',
            flexDirection: 'column',
            alignItems: 'center',
            justifyContent: 'center',
            gap: '16px',
            background: 'linear-gradient(135deg, #fef3c7 0%, #fed7aa 100%)',
            fontFamily: 'system-ui, sans-serif',
        }}>
            <div style={{
                width: '48px',
                height: '48px',
                border: '3px solid #fed7aa',
                borderTopColor: '#d97706',
                borderRadius: '50%',
                animation: 'spin 0.8s linear infinite',
            }} />
            <p style={{ margin: 0, fontSize: '1rem', color: '#92400e', fontWeight: 500 }}>
                Đang xử lý đăng nhập Google...
            </p>
            <style>{`@keyframes spin { to { transform: rotate(360deg); } }`}</style>
        </div>
    );
};

export default GoogleCallback;
```

---

## Bước 6 — `src/components/Login/Login.jsx` (cập nhật)

Thay `<GoogleLogin>` component của SDK `@react-oauth/google` bằng custom button gọi `initiateGoogleLogin()`.

**Chỉ thay 2 phần trong file hiện có:**

### 6a. Sửa phần import (đầu file)

```jsx
// XÓA dòng này:
import { GoogleLogin } from '@react-oauth/google';

// THÊM dòng này:
import { useGoogleLogin } from '../../hook/useGoogleLogin';
```

### 6b. Sửa phần khai báo hook (trong component)

```jsx
// XÓA:
const { login, loginWithGoogle } = useAuth();

// THÊM:
const { login } = useAuth();
const { initiateGoogleLogin } = useGoogleLogin();
```

### 6c. Xóa hàm `handleGoogleSuccess` và `handleGoogleError` cũ

```jsx
// XÓA toàn bộ 2 hàm này:
const handleGoogleSuccess = async (credentialResponse) => { ... };
const handleGoogleError = () => { ... };
```

### 6d. Thay phần render Google Login (trong JSX)

```jsx
{/* XÓA: */}
<div className={styles.googleLoginWrapper}>
  <GoogleLogin
    onSuccess={handleGoogleSuccess}
    onError={handleGoogleError}
    useOneTap={false}
    theme="outline"
    size="large"
    text="continue_with"
    shape="rectangular"
    logo_alignment="left"
  />
</div>

{/* THÊM: */}
<button
  type="button"
  className={styles.socialLoginBtn}
  onClick={initiateGoogleLogin}
  disabled={loading}
>
  <svg width="20" height="20" viewBox="0 0 24 24" fill="none">
    <path d="M22.56 12.25c0-.78-.07-1.53-.2-2.25H12v4.26h5.92c-.26 1.37-1.04 2.53-2.21 3.31v2.77h3.57c2.08-1.92 3.28-4.74 3.28-8.09z" fill="#4285F4"/>
    <path d="M12 23c2.97 0 5.46-.98 7.28-2.66l-3.57-2.77c-.98.66-2.23 1.06-3.71 1.06-2.86 0-5.29-1.93-6.16-4.53H2.18v2.84C3.99 20.53 7.7 23 12 23z" fill="#34A853"/>
    <path d="M5.84 14.09c-.22-.66-.35-1.36-.35-2.09s.13-1.43.35-2.09V7.07H2.18C1.43 8.55 1 10.22 1 12s.43 3.45 1.18 4.93l3.66-2.84z" fill="#FBBC05"/>
    <path d="M12 5.38c1.62 0 3.06.56 4.21 1.64l3.15-3.15C17.45 2.09 14.97 1 12 1 7.7 1 3.99 3.47 2.18 7.07l3.66 2.84c.87-2.6 3.3-4.53 6.16-4.53z" fill="#EA4335"/>
  </svg>
  Đăng nhập với Google
</button>
```

> **Lưu ý:** Class `styles.socialLoginBtn` đã có sẵn trong `Login.module.scss` — không cần thêm SCSS mới.

---

## Bước 7 — `src/App.tsx` (cập nhật)

Thêm route `/auth/google/callback` vào trong `<Route element={<MainLayout />}>`.

```tsx
// Thêm import:
import GoogleCallback from './components/GoogleCallback/GoogleCallback';

// Thêm route (bên trong <Route element={<MainLayout />}>):
<Route path="/auth/google/callback" element={<GoogleCallback />} />
```

**Vị trí thêm** — đặt sau `<Route path="/verify-email" element={<VerifyEmail />} />`:

```tsx
<Route path="/verify-email" element={<VerifyEmail />} />
<Route path="/auth/google/callback" element={<GoogleCallback />} />  {/* ← thêm dòng này */}
```

---

## Bước 8 — `src/.env` (cập nhật)

File `.env` nằm tại `src/.env` (đã thấy trong project). Thêm các biến sau:

```env
# ─── Keycloak (Google OAuth2) ───────────────────────────────
REACT_APP_KEYCLOAK_URL=http://localhost:8180
REACT_APP_KEYCLOAK_REALM=tourism
REACT_APP_KEYCLOAK_CLIENT_ID=tourism-app
REACT_APP_GOOGLE_REDIRECT_URI=http://localhost:3000/auth/google/callback
```

> Sau khi sửa `.env`, **khởi động lại dev server** (`npm start`) để biến môi trường có hiệu lực.

---

## Gỡ cài đặt package không còn dùng

Nếu `@react-oauth/google` chỉ dùng cho Google Login, có thể gỡ:

```bash
npm uninstall @react-oauth/google
```

Đồng thời xóa `<GoogleOAuthProvider>` trong `index.tsx` nếu có.

---

## Luồng đăng nhập Google (tổng quan)

```
User nhấn "Đăng nhập với Google"
        ↓
useGoogleLogin.initiateGoogleLogin()
        ↓
window.location.href → Keycloak /auth?kc_idp_hint=google
        ↓
Keycloak redirect → Google OAuth2 consent screen
        ↓
Google xác thực xong → redirect về:
http://localhost:3000/auth/google/callback?code=xxx
        ↓
GoogleCallback.jsx lấy code từ URL
        ↓
loginWithGoogleCode(code, redirectUri)
        ↓
POST /api/auth/google-login (API Gateway → IAM Service)
        ↓
IAM Service đổi code → token từ Keycloak
IAM Service tìm/tạo user trong DB
        ↓
Trả về { accessToken, refreshToken, user }
        ↓
AuthContext lưu vào localStorage + state
        ↓
Navigate về "/" hoặc "/admin/dashboard"
```

---

## So sánh cách cũ vs cách mới

| | Cách cũ (`@react-oauth/google`) | Cách mới (Backend-mediated) |
|--|--|--|
| **Flow** | Frontend nhận ID Token từ Google | Frontend nhận auth code từ Keycloak |
| **Backend xử lý** | Verify ID Token với Google | Đổi code lấy token từ Keycloak |
| **Keycloak** | Không tham gia | Đóng vai trò Identity Broker |
| **Bảo mật** | Client secret lộ ra frontend | Client secret chỉ ở backend |
| **Endpoint** | `POST /auth/google/login` (idToken) | `POST /auth/google-login` (code + redirectUri) |

---

## Checklist hoàn thành

- [ ] Tạo `src/services/auth/auth.ts`
- [ ] Cập nhật `src/context/AuthContext.jsx` — thêm `loginWithGoogleCode`
- [ ] Tạo `src/hook/useGoogleLogin.js`
- [ ] Tạo `src/components/GoogleCallback/GoogleCallback.jsx`
- [ ] Cập nhật `src/components/Login/Login.jsx` — thay GoogleLogin SDK
- [ ] Cập nhật `src/App.tsx` — thêm route `/auth/google/callback`
- [ ] Cập nhật `src/.env` — thêm biến Keycloak
- [ ] Khởi động lại `npm start`
- [ ] Test: nhấn "Đăng nhập với Google" → hoàn tất flow