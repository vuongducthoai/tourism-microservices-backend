# BÁO CÁO: Trang /information — Đăng nhập cứng để kiểm thử (Dev Mode)

## 1. Mục tiêu

Truy cập trang `http://localhost:3000/information` mà **không cần đăng nhập thật**, tự động gán `userID = 1` để hiện thông tin người dùng thực từ database.

---

## 2. Vấn đề ban đầu

Trang `/information` có guard trong `InformationComponent.jsx`:

```jsx
useEffect(() => {
    if (!loading && !isAuthenticated) {
        navigate('/login');  // redirect nếu chưa đăng nhập
    }
}, [loading, isAuthenticated, navigate]);
```

→ Nếu chưa đăng nhập sẽ bị chuyển sang `/login` ngay lập tức.

---

## 3. Giải pháp: Dev Mode trong AuthContext

### 3.1 Tạo file `.env.local`

**File:** `d:\HK8\tourism_frontend\client-side\.env.local`

```env
REACT_APP_DEV_USER_ID=1
```

> File `.env.local` không bị commit lên git (trong `.gitignore`). Để tắt dev mode → xóa file hoặc set = 0.

### 3.2 Sửa `AuthContext.jsx`

**File:** `src/context/AuthContext.jsx`

Thêm vào đầu hàm `checkAuth()`:

```jsx
const checkAuth = async () => {
    // DEV ONLY: hardcode userId for testing without auth
    const devUserId = parseInt(process.env.REACT_APP_DEV_USER_ID || '0');
    if (devUserId) {
        try {
            const res = await axios.get(`/users/${devUserId}`);
            const data = res.data;
            const realUser = {
                id: data.userID, userId: data.userID, userID: data.userID,
                fullName: data.fullName || '',
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
            // fallback nếu API lỗi
            setUser({ id: devUserId, userId: devUserId, userID: devUserId,
                      fullName: 'Dev User', email: 'dev@test.com', role: 'CUSTOMER' });
            setIsAuthenticated(true);
        }
        setLoading(false);
        return;
    }
    // ... rest of normal auth flow
```

---

## 4. Luồng hoạt động

```
Trình duyệt mở localhost:3000/information
         │
         ▼
AuthContext.checkAuth() chạy ngay khi app load
         │
         ├─ Đọc REACT_APP_DEV_USER_ID = 1
         │
         ▼
GET http://localhost:8080/api/users/1
         │  (qua API Gateway)
         ▼
iam-service trả về:
{
  "userID": 1,
  "fullName": "Nguyễn Văn Admin",
  "phone": "0901000001",
  "email": "admin@tourismvn.com",
  "coinBalance": 0,
  "avatar": "https://res.cloudinary.com/demo/image/upload/avatar_admin.jpg",
  "role": "ADMIN"
}
         │
         ▼
setUser(realUser) + setIsAuthenticated(true)
         │
         ▼
InformationComponent thấy isAuthenticated=true → KHÔNG redirect
         │
         ▼
Hiển thị trang /information với data user 1
```

---

## 5. Dữ liệu hiển thị trên trang

### Header / Avatar section
| Trường | Giá trị hiển thị |
|---|---|
| Ảnh đại diện | `avatar` từ API (Cloudinary URL) |
| Tên | `fullName` = "Nguyễn Văn Admin" |
| Vai trò | "Thành viên Future Travel" |

### Tab "Hồ sơ cá nhân" (PersonalProfile.jsx)
| Trường | Nguồn | Giá trị |
|---|---|---|
| Họ và tên | `userData.fullName` | Nguyễn Văn Admin |
| Số điện thoại | `userData.phone` | 0901000001 |
| Ngày sinh | `userData.dateOfBirth` | 15/03/1985 |
| Email | `userData.email` | admin@tourismvn.com |
| Điểm tích lũy | `userData.coinBalance` | 0 |

### Tab "Danh sách giao dịch" (TransactionList.jsx)
Gọi `GET /api/bookings/user/1?bookingStatus=` → hiện lịch sử đặt tour của user 1.

### Tab "Tour yêu thích" (FavoriteTours.jsx)
Gọi `GET /api/favorite-tours/user/1` → hiện danh sách tour đã thích.

---

## 6. API được gọi khi vào /information

| # | Endpoint | Service | Mục đích |
|---|---|---|---|
| 1 | `GET /api/users/1` | iam-service | Lấy thông tin user (tên, phone, avatar...) |
| 2 | `GET /api/bookings/user/1` | booking-service | Lấy danh sách giao dịch |
| 3 | `GET /api/favorite-tours/user/1` | tour-catalog-service | Lấy tour yêu thích |

---

## 7. Cách tắt Dev Mode

Khi cần dùng auth thật:
1. Mở `.env.local`
2. Xóa dòng `REACT_APP_DEV_USER_ID=1` hoặc đặt `REACT_APP_DEV_USER_ID=0`
3. Restart React dev server

---

## 8. Kết quả kiểm thử

| Bước | Kết quả |
|---|---|
| Truy cập `localhost:3000/information` không đăng nhập | ✅ Vào được, không redirect |
| Hiển thị tên "Nguyễn Văn Admin" trên trang | ✅ |
| Tab giao dịch load được | ✅ (user 1 có dữ liệu booking) |
| Tab yêu thích load được | ✅ (trả về mảng rỗng nếu chưa có) |
| `GET /api/users/1` trả về 200 | ✅ |
| `GET /api/bookings/user/3` trả về 200 với đầy đủ tour+payment info | ✅ |
