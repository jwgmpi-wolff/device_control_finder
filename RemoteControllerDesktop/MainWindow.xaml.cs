using System.IO;
using System.Net.Http;
using System.Windows;
using System.Windows.Controls;
using RemoteControllerDesktop.Services;

namespace RemoteControllerDesktop;

public partial class MainWindow : Window
{
    private HttpClient? _client;
    private AuthService? _auth;
    private CommandService? _commands;
    private string? _token;

    public MainWindow()
    {
        InitializeComponent();
    }

    private async void SignIn_Click(object sender, RoutedEventArgs e)
        => await AuthenticateAsync(register: false);

    private async void Register_Click(object sender, RoutedEventArgs e)
        => await AuthenticateAsync(register: true);

    private async void GoogleSignIn_Click(object sender, RoutedEventArgs e)
    {
        try
        {
            if (!Uri.TryCreate(ServerUrlBox.Text.Trim(), UriKind.Absolute, out var serverUri)
                || serverUri.Scheme != Uri.UriSchemeHttps)
            {
                StatusText.Text = "Enter the deployed RemoteHub HTTPS URL first.";
                return;
            }
            _client?.Dispose();
            _client = new HttpClient { BaseAddress = new Uri(serverUri.AbsoluteUri.TrimEnd('/') + "/") };
            _auth = new AuthService(_client);
            _commands = new CommandService(_client);
            StatusText.Text = "Opening Google sign-in...";
            var result = await _auth.GoogleLoginAsync();
            _token = result.Token;
            await LoadDevicesAsync();
            StatusText.Text = "Signed in with Google.";
        }
        catch (HttpRequestException ex)
        {
            StatusText.Text = $"Google sign-in failed: {ex.Message}";
        }
        catch (InvalidOperationException ex)
        {
            StatusText.Text = $"Google sign-in failed: {ex.Message}";
        }
        catch (FileNotFoundException ex)
        {
            StatusText.Text = $"{ex.Message} Download a Google Desktop OAuth client and save it at that path.";
        }
    }

    private async Task AuthenticateAsync(bool register)
    {
        try
        {
            if (!Uri.TryCreate(ServerUrlBox.Text.Trim(), UriKind.Absolute, out var serverUri)
                || serverUri.Scheme != Uri.UriSchemeHttps)
            {
                StatusText.Text = "Enter the deployed RemoteHub HTTPS URL first.";
                return;
            }
            _client?.Dispose();
            _client = new HttpClient { BaseAddress = new Uri(serverUri.AbsoluteUri.TrimEnd('/') + "/") };
            _auth = new AuthService(_client);
            _commands = new CommandService(_client);
            StatusText.Text = register ? "Creating account..." : "Signing in...";
            var result = register
                ? await _auth.RegisterAsync(EmailBox.Text.Trim(), PasswordBox.Password)
                : await _auth.LoginAsync(EmailBox.Text.Trim(), PasswordBox.Password);
            _token = result.Token;
            await LoadDevicesAsync();
            StatusText.Text = register ? "Account created and signed in." : "Signed in.";
        }
        catch (HttpRequestException ex)
        {
            StatusText.Text = $"Sign-in failed: {ex.Message}";
        }
        catch (InvalidOperationException ex)
        {
            StatusText.Text = $"Sign-in failed: {ex.Message}";
        }
    }

    private async Task LoadDevicesAsync()
    {
        if (_token is null || _commands is null) return;
        var devices = await _commands.GetDevicesAsync(_token);
        DevicePanel.Children.Clear();
        foreach (var device in devices)
        {
            var card = new StackPanel { Width = 390, Margin = new Thickness(0, 0, 16, 16) };
            card.Children.Add(new TextBlock
            {
                Text = device.Name,
                FontSize = 18,
                FontWeight = FontWeights.SemiBold,
                Margin = new Thickness(0, 0, 0, 8)
            });
            var actions = new WrapPanel();
            AddRequestButton(actions, device, "CAMERA", "Request camera");
            AddRequestButton(actions, device, "MICROPHONE", "Request audio");
            AddRequestButton(actions, device, "LOCATION_ONCE", "Request one-time location");
            card.Children.Add(actions);
            DevicePanel.Children.Add(card);
        }
        StatusText.Text = $"Loaded {devices.Count} device(s).";
    }

    private async void RegisterDevice_Click(object sender, RoutedEventArgs e)
    {
        try
        {
            if (_token is null || _commands is null)
            {
                StatusText.Text = "Sign in before registering a target device.";
                return;
            }
            var name = DeviceNameBox.Text.Trim();
            if (string.IsNullOrWhiteSpace(name))
            {
                StatusText.Text = "Enter a target device name.";
                return;
            }
            var registered = await _commands.RegisterDeviceAsync(_token, name);
            DeviceNameBox.Clear();
            StatusText.Text = $"Pairing token (copy to RemoteAgent now): {registered.AgentToken}";
            await LoadDevicesAsync();
        }
        catch (HttpRequestException ex)
        {
            StatusText.Text = $"Device registration failed: {ex.Message}";
        }
        catch (InvalidOperationException ex)
        {
            StatusText.Text = $"Device registration failed: {ex.Message}";
        }
    }

    private void AddRequestButton(Panel parent, Device device, string capability, string caption)
    {
        var button = new Button
        {
            Content = caption,
            Margin = new Thickness(0, 0, 8, 8),
            Padding = new Thickness(10, 6, 10, 6)
        };
        button.Click += async (_, _) =>
        {
            try
            {
                if (_token is null) return;
                button.IsEnabled = false;
                if (_commands is null) return;
                var request = await _commands.RequestAsync(_token, device.Id, capability);
                StatusText.Text = $"Request pending owner approval ({request.SessionId}).";
            }
            catch (HttpRequestException ex)
            {
                StatusText.Text = $"Request failed: {ex.Message}";
            }
            catch (InvalidOperationException ex)
            {
                StatusText.Text = $"Request failed: {ex.Message}";
            }
            finally
            {
                button.IsEnabled = true;
            }
        };
        parent.Children.Add(button);
    }
}
