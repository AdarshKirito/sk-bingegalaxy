// Utility Functions
const showAlert = (message, type = 'error') => {
    const alertBox = document.getElementById('alertBox');
    alertBox.textContent = message;
    alertBox.className = `alert alert-${type === 'error' ? 'error' : 'success'}`;
    alertBox.classList.remove('hidden');

    setTimeout(() => {
        alertBox.classList.add('hidden');
    }, 5000);
};

const toggleLoading = (show) => {
    const submitText = document.getElementById('submitText');
    const loadingSpinner = document.getElementById('loadingSpinner');
    
    if (show) {
        submitText.classList.add('hidden');
        loadingSpinner.classList.remove('hidden');
    } else {
        submitText.classList.remove('hidden');
        loadingSpinner.classList.add('hidden');
    }
};

const togglePassword = (fieldId) => {
    const field = document.getElementById(fieldId);
    field.type = field.type === 'password' ? 'text' : 'password';
};

// Form Validation
const validateForm = (formData) => {
    const { name, email, phone, password, confirmPassword } = formData;

    if (name.length < 2) {
        return 'Name must be at least 2 characters long';
    }

    if (!/^\w+([.-]?\w+)*@\w+([.-]?\w+)*(\.\w{2,3})+$/.test(email)) {
        return 'Please enter a valid email address';
    }

    if (!/^[0-9]{10}$/.test(phone)) {
        return 'Please enter a valid 10-digit phone number';
    }

    if (password.length < 6) {
        return 'Password must be at least 6 characters long';
    }

    if (password !== confirmPassword) {
        return 'Passwords do not match';
    }

    return null;
};

// API Calls
const apiCall = async (url, method, data) => {
    const options = {
        method,
        headers: {
            'Content-Type': 'application/json',
        },
    };

    if (data) {
        options.body = JSON.stringify(data);
    }

    const response = await fetch(url, options);
    return await response.json();
};

// Registration Handler
document.getElementById('registerForm')?.addEventListener('submit', async (e) => {
    e.preventDefault();
    
    const formData = {
        name: document.getElementById('name').value.trim(),
        email: document.getElementById('email').value.trim(),
        phone: document.getElementById('phone').value.trim(),
        password: document.getElementById('password').value,
        confirmPassword: document.getElementById('confirmPassword').value
    };

    // Validate form
    const validationError = validateForm(formData);
    if (validationError) {
        showAlert(validationError);
        return;
    }

    toggleLoading(true);

    try {
        const result = await apiCall('/api/auth/register', 'POST', {
            name: formData.name,
            email: formData.email,
            phone: formData.phone,
            password: formData.password
        });

        if (result.success) {
            showAlert('Registration successful! Redirecting...', 'success');
            
            // Store user data in localStorage
            localStorage.setItem('token', result.token);
            localStorage.setItem('user', JSON.stringify(result.user));
            
            // Redirect based on role
            setTimeout(() => {
                if (result.user.role === 'admin') {
                    window.location.href = '/admin-dashboard';
                } else {
                    window.location.href = '/dashboard';
                }
            }, 2000);
        } else {
            showAlert(result.message);
        }
    } catch (error) {
        console.error('Registration error:', error);
        showAlert('Network error. Please try again.');
    } finally {
        toggleLoading(false);
    }
});

// Login Handler (for login page)
// document.getElementById('loginForm')?.addEventListener('submit', async (e) => {
//     e.preventDefault();
    
//     const formData = {
//         email: document.getElementById('email').value.trim(),
//         password: document.getElementById('password').value
//     };

//     if (!formData.email || !formData.password) {
//         showAlert('Please fill in all fields');
//         return;
//     }

//     toggleLoading(true);

//     try {
//         const result = await apiCall('/api/auth/login', 'POST', formData);

//         if (result.success) {
//             showAlert('Login successful! Redirecting...', 'success');
            
//             localStorage.setItem('token', result.token);
//             localStorage.setItem('user', JSON.stringify(result.user));
            
//             setTimeout(() => {
//                 if (result.user.role === 'admin') {
//                     window.location.href = '/admin-dashboard';
//                 } else {
//                     window.location.href = '/dashboard';
//                 }
//             }, 2000);
//         } else {
//             showAlert(result.message);
//         }
//     } catch (error) {
//         console.error('Login error:', error);
//         showAlert('Network error. Please try again.');
//     } finally {
//         toggleLoading(false);
//     }
// });

// Login Handler (for login page)
document.getElementById('loginForm')?.addEventListener('submit', async (e) => {
    e.preventDefault();
    
    const formData = {
        email: document.getElementById('email').value.trim(),
        password: document.getElementById('password').value
    };

    if (!formData.email || !formData.password) {
        showAlert('Please fill in all fields');
        return;
    }

    toggleLoading(true);

    try {
        const result = await apiCall('/api/auth/login', 'POST', formData);

        console.log('Login result:', result); // Debug log

        if (result.success) {
            showAlert('Login successful! Redirecting...', 'success');
            
            localStorage.setItem('token', result.token);
            localStorage.setItem('user', JSON.stringify(result.user));
            
            setTimeout(() => {
                if (result.user.role === 'admin') {
                    window.location.href = '/admin-dashboard';  // ✅ Fixed path
                } else {
                    window.location.href = '/dashboard';        // ✅ Fixed path
                }
            }, 1500); // Reduced timeout for better UX
        } else {
            showAlert(result.message);
        }
    } catch (error) {
        console.error('Login error:', error);
        showAlert('Network error. Please try again.');
    } finally {
        toggleLoading(false);
    }
});

// Forgot Password Handler
document.getElementById('forgotPasswordForm')?.addEventListener('submit', async (e) => {
    e.preventDefault();
    
    const email = document.getElementById('email').value.trim();
    
    if (!email) {
        showAlert('Please enter your email address');
        return;
    }

    toggleLoading(true);

    try {
        const result = await apiCall('/api/auth/forgot-password', 'POST', { email });

        if (result.success) {
            showAlert('Password reset instructions sent to your email', 'success');
        } else {
            showAlert(result.message);
        }
    } catch (error) {
        console.error('Forgot password error:', error);
        showAlert('Network error. Please try again.');
    } finally {
        toggleLoading(false);
    }
});

// Check if user is already logged in
// const checkAuth = () => {
//     const token = localStorage.getItem('token');
//     const user = JSON.parse(localStorage.getItem('user') || '{}');
//     const currentPath = window.location.pathname;

//      console.log('Auth Check:', { 
//         hasToken: !!token, 
//         userRole: user.role, 
//         currentPath: currentPath 
//     });
    
//     if (token && user.role) {
//         if (user.role === 'admin' && !window.location.pathname.includes('admin')) {
//             window.location.href = 'admin-dashboard';
//         } else if (user.role === 'customer' && !window.location.pathname.includes('admin')) {
//             window.location.href = 'dashboard';
//         }
//     }
// };


// Check if user is already logged in - FIXED VERSION
// const checkAuth = () => {
//     const token = localStorage.getItem('token');
//     const user = JSON.parse(localStorage.getItem('user') || '{}');
//     const currentPath = window.location.pathname;
    
//     console.log('Auth Check:', { 
//         hasToken: !!token, 
//         userRole: user.role, 
//         currentPath: currentPath 
//     });
    
//     // If user is logged in
//     if (token && user.role) {
//         // If on login page, redirect to appropriate dashboard
//         if (currentPath.includes('login')) {
//             if (user.role === 'admin') {
//                 window.location.href = '/admin-dashboard';
//             } else {
//                 window.location.href = '/dashboard';
//             }
//         }
//         // If user is on wrong dashboard type, redirect to correct one
//         else if (user.role === 'admin' && currentPath.includes('dashboard') && !currentPath.includes('admin')) {
//             window.location.href = '/admin-dashboard';
//         }
//         else if (user.role === 'customer' && currentPath.includes('admin')) {
//             window.location.href = '/dashboard';
//         }
//     } 
//     // If user is NOT logged in but trying to access protected pages
//     else {
//         if (currentPath.includes('dashboard') || currentPath.includes('admin') || 
//             currentPath.includes('booking') || currentPath.includes('payment')) {
//             window.location.href = '/login';
//         }
//     }
// };


//===================================================================================================================


// Check if user is already logged in - FIXED VERSION
const checkAuth = () => {
    const token = localStorage.getItem('token');
    const user = JSON.parse(localStorage.getItem('user') || '{}');
    const currentPath = window.location.pathname;
    
    console.log('=== AUTH DEBUG START ===');
    console.log('Token:', token);
    console.log('User:', user);
    console.log('User Role:', user.role);
    console.log('Current Path:', currentPath);
    console.log('Includes admin?:', currentPath.includes('admin'));
    console.log('Includes login?:', currentPath.includes('login'));
    console.log('=== AUTH DEBUG END ===');
    
    // If user is logged in
    if (token && user.role) {
        // If on login page, redirect to appropriate dashboard
        if (currentPath.includes('login')) {
            console.log('Redirecting from login to dashboard');
            if (user.role === 'admin') {
                window.location.href = '/admin-dashboard';
            } else {
                window.location.href = '/dashboard';
            }
        }
        // If user is on wrong dashboard type, redirect to correct one
        else if (user.role === 'admin' && currentPath.includes('dashboard') && !currentPath.includes('admin')) {
            console.log('Admin on user dashboard, redirecting to admin');
            window.location.href = '/admin-dashboard';
        }
        else if (user.role === 'customer' && currentPath.includes('admin')) {
            console.log('User on admin page, redirecting to user dashboard');
            window.location.href = '/dashboard';
        }
        else {
            console.log('User is on correct page, no redirect needed');
        }
    } 
    // If user is NOT logged in but trying to access protected pages
    else {
        if (currentPath.includes('dashboard') || currentPath.includes('admin') || 
            currentPath.includes('booking') || currentPath.includes('payment')) {
            console.log('Not logged in, redirecting to login');
            window.location.href = '/login';
        }
    }
};



// Initialize auth check when page loads
document.addEventListener('DOMContentLoaded', checkAuth);