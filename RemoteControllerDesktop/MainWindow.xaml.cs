using System.Net.Http;
using System.Windows;
using System.Windows.Controls;
using RemoteControllerDesktop.Services;

namespace RemoteControllerDesktop;

public partial class MainWindow : Window
{
    private readonly HttpClient _client = new() { BaseAddress = new Uri("https://remotehub.example/") };
    private readonly AuthService _auth;
    private readonly CommandService _commands;
    private string? _token;

    public MainWindow()
    {
        InitializeComponent();
        _auth = new AuthService(_client);
        _commands = new CommandService(_client);
    }

    private async void SignIn_Click(object sender, RoutedEventArgs e)
    {
        try
        {
            StatusText.Text = "Signing in...";
            var result = await _auth.LoginAsync(EmailBox.Text.Trim(), PasswordBox.Password);
            _token = result.Token;
            await LoadDevicesAsync();
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
        if (_token is null) return;
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
